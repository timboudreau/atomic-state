/*
 * The MIT License
 *
 * Copyright 2022 Mastfrog Technologies.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.mastfrog.atomicstate.processor;

import com.mastfrog.annotation.AnnotationUtils;
import static com.mastfrog.annotation.AnnotationUtils.capitalize;
import static com.mastfrog.annotation.AnnotationUtils.simpleName;
import static com.mastfrog.atomicstate.processor.AtomicStateAnnotationProcessor.ATOMIC_STATE_ANNO;
import com.mastfrog.java.vogon.ClassBuilder;
import com.mastfrog.java.vogon.ClassBuilder.BlockBuilder;
import com.mastfrog.util.service.ServiceProvider;
import com.mastfrog.util.strings.Strings;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import static java.lang.Math.floor;
import static java.lang.Math.log;
import static java.nio.charset.StandardCharsets.UTF_8;
import java.util.ArrayList;
import static java.util.Collections.emptyList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import static javax.lang.model.element.Modifier.DEFAULT;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.JavaFileObject;

/**
 *
 * @author Tim Boudreau
 */
@ServiceProvider(Processor.class)
@SupportedAnnotationTypes(ATOMIC_STATE_ANNO)
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class AtomicStateAnnotationProcessor extends AbstractProcessor {

    private static final long SER_VERSION = 0;
    private AnnotationUtils utils;
    private final Map<TypeElement, StateModel> models = new HashMap<>();

    private static final String PKG = "com.mastfrog.atomicstate";

    static final String ATOMIC_STATE_ANNO = PKG + ".AtomicState";
    private static final String VALUE_RANGE_ANNO = PKG + ".ValueRange";

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        utils = new AnnotationUtils(processingEnv, getSupportedAnnotationTypes(), AtomicStateAnnotationProcessor.class);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {

        utils.findAnnotatedElements(roundEnv, getSupportedAnnotationTypes())
                .forEach(item -> {
                    AnnotationMirror anno = utils.findAnnotationMirror(item, getSupportedAnnotationTypes().iterator().next());
                    handleItem(item, roundEnv, anno);
                });

        try {
            for (Map.Entry<TypeElement, StateModel> e : models.entrySet()) {
                ClassBuilder<String> cb = e.getValue().generator().sortMembers();
                Filer filer = utils.processingEnv().getFiler();
                try {
                    JavaFileObject src = filer.createSourceFile(cb.fqn(), e.getKey());
                    try ( OutputStream out = src.openOutputStream()) {
                        out.write(cb.build().getBytes(UTF_8));
                    }
                } catch (IOException ex) {
                    utils.fail(ex + "", e.getKey());
                    ex.printStackTrace();
                }

                cb = e.getValue().generateStateHolder().sortMembers();
                try {
                    JavaFileObject src = filer.createSourceFile(cb.fqn(), e.getKey());
                    try ( OutputStream out = src.openOutputStream()) {
                        out.write(cb.build().getBytes(UTF_8));
                    }
                } catch (IOException ex) {
                    utils.fail(ex + "", e.getKey());
                    ex.printStackTrace();
                }
                cb = e.getValue().generateListener();
                if (cb != null) {
                    cb.sortMembers();
                    try {
                        JavaFileObject src = filer.createSourceFile(cb.fqn(), e.getKey());
                        try ( OutputStream out = src.openOutputStream()) {
                            out.write(cb.build().getBytes(UTF_8));
                        }
                    } catch (IOException ex) {
                        utils.fail(ex + "", e.getKey());
                        ex.printStackTrace();
                    }
                }
            }
        } finally {
            models.clear();
        }
        return true;
    }

    StateModel model(AnnotationMirror mir, TypeElement el) {
        return models.computeIfAbsent(el, e -> new StateModel(e, mir));
    }

    private void handleItem(Element item, RoundEnvironment roundEnv, AnnotationMirror anno) {
        switch (item.getKind()) {
            case INTERFACE:
                break;
            default:
                utils.fail("Annotation not applicable to a " + item.getKind() + ": " + item + " with " + anno);
                return;
        }
        StateModel model = null;
        for (Element child : item.getEnclosedElements()) {
            Set<Modifier> mods = child.getModifiers();
            if (!mods.contains(Modifier.STATIC) && child.getKind() == ElementKind.METHOD) {
                ExecutableElement ee = (ExecutableElement) child;

                TypeMirror ret = ee.getReturnType();
                if ("void".equals(ret.toString())) {
                    if (mods.contains(Modifier.DEFAULT)) {
                        continue;
                    }
                    utils.fail("AtomicState interfaces cannot have methods that return 'void' unless they have a default implementation.");
                }
                if (!validateType(ee, anno)) {
                    continue;
                }

                if (model == null) {
                    model = model(anno, (TypeElement) item);
                }

                model.addItem(ee.getSimpleName().toString(), ee);
            }
        }
    }

    private TypeMirror enumType() {
        return utils.erasureOf(utils.processingEnv().getElementUtils().getTypeElement(Enum.class.getName()).asType());
    }

    private boolean validateType(ExecutableElement el, AnnotationMirror via) {
        TypeMirror mir = el.getReturnType();

        switch (mir.getKind()) {
            case BYTE:
            case FLOAT:
            case SHORT:
            case BOOLEAN:
            case INT:
            case CHAR:
                return true;
            default:
                if (el.getModifiers().contains(Modifier.DEFAULT)) {
                    return false;
                }
                if (utils.isAssignable(mir, enumType())) {
                    return true;
                }
                utils.fail("Type " + mir + " is too large or inapproriate to be part of an AtomicState");
                return false;
        }
    }

    final class BitsElement {

        final int startingBit;
        final int bitsRequired;
        final String name;
        final ExecutableElement origin;
        final AnnotationMirror mir;
        final boolean isEnum;
        final Optional<ValueRangeProxy> range;

        public BitsElement(int startingBit, int bitsRequired, String name, ExecutableElement origin, AnnotationMirror mir, boolean isEnum) {
            this.startingBit = startingBit;
            this.bitsRequired = bitsRequired;
            this.name = name;
            this.origin = origin;
            this.mir = mir;
            this.isEnum = isEnum;
            range = valueRange(origin);
        }

        void generateWriteMethod(boolean isLong, ClassBuilder<String> cb) {
            String valType = isLong ? "long" : "int";
            String maskFieldName = name.toUpperCase() + "_MASK";
            String inputName;
            boolean needCastAndBoundsCheck;
            range.ifPresent(rng -> {
                rng.generateFields(name, isLong, cb);
            });
            switch (origin.getReturnType().getKind()) {
                case BYTE:
                case SHORT:
                    inputName = "int";
                    needCastAndBoundsCheck = true;
                    break;
                default:
                    inputName = origin.getReturnType().toString();
                    needCastAndBoundsCheck = false;
                    break;

            }
            String boxedType = capitalize(origin.getReturnType().toString());

            String dox = "Creates a new instance of " + cb.className()
                    + " with " + name + " set to the passed value."
                    + (needCastAndBoundsCheck ? " Note that while this methd takes <code>int</code>, "
                            + "the passed value must be within the bounds of " + boxedType + ".MIN_VALUE and "
                            + boxedType + ".MAX_VALUE or an IllegalArgumentException will be thrown." : "")
                    + "\n@param newValue the new value of " + name
                    + "\n@return a new instance of " + cb.className() + " or <code>this</code> if "
                    + "the value is the same as this instance's value of " + name + ".";

            cb.method("with" + capitalize(name), mth -> {
                mth.withModifier(PUBLIC)
                        .addArgument(simpleName(inputName), "newValue")
                        .docComment(dox)
                        .returning(cb.className())
                        .body(bb -> {
                            String valueName;
                            if (needCastAndBoundsCheck) {
                                valueName = "realValue";
                                if (range.isPresent()) {
                                    range.get().generateValidationTest(name, "newValue", bb);
                                } else {
                                    bb.iff().booleanExpression("newValue < " + boxedType + ".MIN_VALUE"
                                            + " || newValue > " + boxedType + ".MAX_VALUE")
                                            .andThrow(nb -> {
                                                nb.withStringConcatentationArgument("Value")
                                                        .appendExpression("newValue")
                                                        .append(" is outside the bounds of ")
                                                        .append(boxedType)
                                                        .endConcatenation()
                                                        .ofType("IllegalArgumentException");
                                            }).endIf();
                                }
                                bb.declare("realValue")
                                        .initializedWith("(" + origin.getReturnType() + ") newValue")
                                        .as(origin.getReturnType() + "");
                            } else {
                                if (range.isPresent()) {
                                    range.get().generateValidationTest(name, "newValue", bb);
                                }
                                valueName = "newValue";
                            }
                            if (origin.getReturnType().getKind() == TypeKind.BOOLEAN) {
                                bb.iff().booleanExpression(name + "()")
                                        .returningThis().endIf();
                            } else {
                                bb.iff().booleanExpression(name + "() == " + valueName)
                                        .returningThis().endIf();
                            }
                            bb.declare("masked")
                                    .initializedWith("this.value & ~" + maskFieldName)
                                    .as(valType);

                            if (isEnum) {
                                ClassBuilder.DeclarationBuilder<?> nue = bb.declare("nue");

                                nue.initializedWith("masked | ( "
                                        + (isLong ? "(long)" : "")
                                        + "newValue.ordinal() << " + startingBitFieldName() + ")");
                                nue.as(valType);
                                bb.returningNew(nb -> {
                                    nb.withArgument("nue")
                                            .ofType(cb.className());
                                });
                            } else if (origin.getReturnType().getKind() == TypeKind.BOOLEAN) {
                                ClassBuilder.BlockBuilder<?> nue = bb.declare("nue")
                                        .initializedWith("masked").as(valType);
                                if (startingBit == 0) {
                                    bb.iff().booleanExpression(valueName)
                                            .statement("nue |= 1")
                                            .endIf();

                                } else {
                                    if (isLong) {
                                        bb.iff().booleanExpression(valueName)
                                                .statement("nue |= 1L << " + startingBitFieldName())
                                                .endIf();
                                    } else {
                                        bb.iff().booleanExpression(valueName)
                                                .statement("nue |= 1 << " + startingBitFieldName())
                                                .endIf();
                                    }
                                }
                                bb.returningNew(nb -> {
                                    nb.withArgument("nue")
                                            .ofType(cb.className());
                                });
                            } else {
                                bb.declare("nue")
                                        .initializedWith("masked").as(valType);
                                String finalValueName;
                                if (range.isPresent()) {
                                    String s = range.get().toStorableValue(name, valueName, isLong);
                                    bb.declare("_converted")
                                            .initializedWith(s)
                                            .as(isLong ? "long" : "int");
                                    finalValueName = "_converted";
                                } else {
                                    finalValueName = valueName;
                                }
                                bb.returningNew(nb -> {
                                    nb.withArgument("nue | "
                                            + (startingBit == 0 ? finalValueName : "(" + finalValueName + " << " + startingBitFieldName() + ")")
                                    )
                                            .ofType(cb.className());
                                });
                            }
                        });
            });
        }

        @Override
        public String toString() {
            return "BitsElement{" + "startingBit=" + startingBit
                    + ", bitsRequired=" + bitsRequired + ", name="
                    + name + ", origin=" + origin + ", mir=" + mir
                    + ", isEnum=" + isEnum + '}';
        }

        String startingBitFieldName() {
            return name.toUpperCase() + "_STARTING_BIT";
        }

        String maskFieldName() {
            return name.toUpperCase() + "_MASK";
        }

        void contributeValidationClause(boolean isLong, BlockBuilder<?> bb) {
            if (isEnum) {
                int count = getEnumMembers(origin.getReturnType()).size();
                String varName = name + "Ordinal";
                bb.declare(varName)
                        .initializedWith("(int)" + "((value & "
                                + maskFieldName() + ") >>> " + startingBitFieldName() + ")")
                        .as("int");
                bb.iff().booleanExpression(varName + " < 0 || " + varName + " >= " + count)
                        .andThrow(nb -> {
                            nb.withStringConcatentationArgument("Ordinal for " + name
                                    + " must be >= 0 and < " + count)
                                    .append(" but have ")
                                    .appendExpression(varName)
                                    .append(" in ")
                                    .appendInvocationOf("toBinaryString")
                                    .withArgument(varName)
                                    .on("Long")
                                    .endConcatenation()
                                    .ofType("IllegalArgumentException");
                        }).endIf();

            }
            range.ifPresent(rng -> {
                String nv = name + "Value";
                bb.declare(nv)
                        .initializedWith(name.toUpperCase() + "_MIN + ((value & "
                                + maskFieldName() + ")"
                                + (startingBit == 0 ? ")"
                                        : " >>> " + startingBitFieldName() + ")"))
                        .as("long");
                rng.generateValidationTest(name, nv, bb);
            });
        }

        void generateReadMethod(boolean isLong, ClassBuilder<String> cb) {
            String valType = isLong ? "long" : "int";
            String maskFieldName = maskFieldName();
            cb.field(maskFieldName, fld -> {
                long mask = 0;
                for (int i = 0; i < bitsRequired; i++) {
                    int bit = i + startingBit;
                    mask |= 1L << bit;
                }
                Number nmask;
                String str;
                if (isLong) {
                    nmask = mask;
                    str = asBinaryString(mask);
                } else {
                    nmask = (int) mask;
                    str = asBinaryString((int) mask);
                }
                fld.docComment("" + mask + " bits " + this.bitsRequired
                        + " for " + origin.getReturnType());
                fld.withModifier(PRIVATE, STATIC, FINAL)
                        .initializedTo(str).ofType(valType);
            });

            cb.field(startingBitFieldName())
                    .withModifier(FINAL, PRIVATE, STATIC)
                    .initializedWith(startingBit);

            cb.overridePublic(name, mth -> {
                String ret = origin.getReturnType().toString();
                if (ret.indexOf('.') > 0) {
                    int ix = ret.lastIndexOf('.');
                    String pkg = ret.substring(0, ix);
                    if (!pkg.equals("java.lang") && !pkg.equals(cb.packageName())) {
                        cb.importing(ret);
                    }
                }
                mth.returning(simpleName(ret))
                        .body(bb -> {
                            String nm = name + "Value";
                            if (startingBit == 0) {
                                bb.declare(nm)
                                        .initializedWith("(value & "
                                                + maskFieldName + ")")
                                        .as(valType);

                            } else {
                                if (isEnum) {
                                    bb.declare(nm)
                                            .initializedWith((isLong ? "(int)" : "") + "((value & "
                                                    + maskFieldName + ") >>> " + startingBitFieldName() + ")")
                                            .as("int");

                                } else {
                                    if (origin.getReturnType().getKind() == TypeKind.BOOLEAN) {
                                        bb.declare(nm)
                                                .initializedWith("(int) ((value & "
                                                        + maskFieldName + ") >>> " + startingBitFieldName() + ")")
                                                .as("int");

                                    } else {
                                        bb.declare(nm)
                                                .initializedWith("(value & "
                                                        + maskFieldName + ") >>> " + startingBitFieldName())
                                                .as(valType);
                                    }
                                }
                            }

                            if (isEnum) {
                                List<String> consts = getEnumMembers(origin);
                                String typeName = simpleName(origin.getReturnType().toString());
                                bb.switchingOn(nm, sw -> {
                                    for (int i = 0; i < consts.size(); i++) {
                                        int ix = i;
                                        sw.inCase(i, cs -> {
                                            cs.returningField(consts.get(ix)).of(typeName);
                                        });
                                    }
                                    sw.inDefaultCase(cs -> {
                                        cs.andThrow(nb -> {
                                            nb.withStringConcatentationArgument("Not a valid enum index ")
                                                    .appendExpression("value")
                                                    .append(" on ")
                                                    .append(typeName)
                                                    .append(" which has ")
                                                    .append(consts.size())
                                                    .append(" enum constants: ")
                                                    .append(Strings.join(", ", consts))
                                                    .endConcatenation()
                                                    .ofType("IllegalArgumentException");
                                        });
                                    });
                                });
                            } else if (origin.getReturnType().getKind() == TypeKind.BOOLEAN) {
                                bb.returning(nm + " != 0");
                            } else {
                                if (range.isPresent()) {
                                    ValueRangeProxy px = range.get();
                                    bb.returning(px.toReturnableValue(name, nm, isLong));
                                } else {
                                    bb.returning("(" + origin.getReturnType() + ") " + nm);
                                }
                            }
                        });
            });
        }

    }

    final class StateModel {

        private final Map<String, ExecutableElement> methodForName = new TreeMap<>();
        private final TypeElement el;
        private final AnnotationMirror on;

        public StateModel(TypeElement el, AnnotationMirror on) {
            this.el = el;
            this.on = on;
        }

        private long serialVersionUid() {
            long val = SER_VERSION;
            for (BitsElement be : toElements()) {
                String typeName = be.origin.asType().toString();
                long hc = typeName.hashCode();
                hc >>>= be.startingBit;
                val = (28687 * val) ^ hc;
            }
            return val;
        }

        ClassBuilder<String> generateListener() {
            boolean changeSupport = utils.annotationValue(on, "generateChangeSupport", Boolean.class, false);

            if (!changeSupport) {
                return null;
            }
            String stateName = el.getSimpleName() + "State";
            String listenerClassName = stateName + "Listener";
            ClassBuilder<String> listenerClass = ClassBuilder.forPackage(utils.packageName(el))
                    .named(listenerClassName)
                    .withModifier(PUBLIC);

            listenerClass
                    .toInterface()
                    .annotatedWith("FunctionalInterface").closeAnnotation()
                    .docComment("Listener interface for detecting changes in a " + stateName + ", "
                            + "which must be passed at construction-time to the constructor of " + stateName
                            + ".\nA listener throwing an exception will not prevent a state change from taking place."
                            + "\nThe listener is only called if a mutation method actually results in the state "
                            + "changing.  That, however, does not guarantee that another atomic state change has "
                            + "not occurred since the state change that triggered the call a listener, so a "
                            + "<code>Supplier</code> is included in the method signature which can fetch the "
                            + "current (though also, once you read it, historical) state of the " + stateName + ".")
                    .method("onChange", mth -> {
                        listenerClass.importing(Supplier.class);
                        mth.docComment("Callback invoked after the state has been changed, if the "
                                + "result differs from the previous state."
                                + "\n@param previousState the previous state"
                                + "\n@param changedToState the state changed to"
                                + "\n@param currentState getter for the current state, which may differ from <code>changedToState</code>");
                        mth.addArgument(stateName, "previousState")
                                .addArgument(stateName, "changedToState")
                                .addArgument("Supplier<" + stateName + ">", "currentState")
                                .closeMethod();
                    })
                    .method("async", mth -> {
                        listenerClass.importing(ExecutorService.class);
                        mth.withModifier(STATIC)
                                .returning(listenerClassName)
                                .addArgument("ExecutorService", "executor")
                                .addArgument(listenerClassName, "delegate")
                                .docComment("Create a listener which will be invoked asynchronously in the passed executor."
                                        + "\n@param executor An executor"
                                        + "\n@param delegate The listener which should be invoked asynchronously"
                                        + "\n@return A listener which wraps the delegate an executes it asynchronously"
                                )
                                .body(bb -> {
                                    bb.returningLambda()
                                            .withArgument("previousState")
                                            .withArgument("changedToState")
                                            .withArgument("currentStateGetter")
                                            .body(lbb -> {
                                                lbb.invoke("submit")
                                                        .withLambdaArgument(sublbb -> {
                                                            sublbb.body(b -> {
                                                                b.invoke("onChange")
                                                                        .withArgument("previousState")
                                                                        .withArgument("changedToState")
                                                                        .withArgument("currentStateGetter")
                                                                        .on("delegate");
                                                            });
                                                        })
                                                        .on("executor");
                                            });
                                });
                    })
                    .method("async", mth -> {
                        listenerClass.importing(ForkJoinPool.class);
                        mth.docComment("Create a listener which will asynchronously call the "
                                + "passed listener on state changes using {@link ForkJoinPool#commonPool}."
                                + "\n@param delegate The listener which should be invoked asynchronously"
                                + "\n@return A listener which wraps the delegate an executes it asynchronously"
                        ).withModifier(STATIC)
                                .returning(listenerClassName)
                                .addArgument(listenerClassName, "delegate")
                                .body(bb -> {
                                    bb.returningLambda()
                                            .withArgument("previousState")
                                            .withArgument("changedToState")
                                            .withArgument("currentStateGetter")
                                            .body(lbb -> {
                                                lbb.invoke("submit")
                                                        .withLambdaArgument(sublbb -> {
                                                            sublbb.body(b -> {
                                                                b.invoke("onChange")
                                                                        .withArgument("previousState")
                                                                        .withArgument("changedToState")
                                                                        .withArgument("currentStateGetter")
                                                                        .on("delegate");
                                                            });
                                                        })
                                                        .onInvocationOf("commonPool")
                                                        .on("ForkJoinPool");
                                            });
                                });
                    })
                    .method("andThen", mth -> {
                        mth.withModifier(DEFAULT)
                                .docComment("Chain this Listener and another, returning a Listener that "
                                        + "delegates to both.\n@param next Another listener"
                                        + "\n@return A wrapper listener around both listeners."
                                        + "\n@throws IllegalArgumentException if the passed listener == this")
                                .addArgument(listenerClassName, "next")
                                .returning(listenerClassName)
                                .body(bb -> {
                                    bb.iff().booleanExpression("next == this")
                                            .andThrow(nb -> {
                                                nb.withStringLiteral("Cannot chain with self")
                                                        .ofType("IllegalArgumentException");
                                            }).endIf();
                                    bb.returningLambda(lb -> {
                                        lb.withArgument("old")
                                                .withArgument("nue")
                                                .withArgument("getter")
                                                .body(lbb -> {
                                                    lbb.invoke("onChange")
                                                            .withArgument("old")
                                                            .withArgument("nue")
                                                            .withArgument("getter")
                                                            .on("this");
                                                    lbb.invoke("onChange")
                                                            .withArgument("old")
                                                            .withArgument("nue")
                                                            .withArgument("getter")
                                                            .on("next");
                                                });
                                    });
                                });
                    });
            return listenerClass;
        }

        ClassBuilder<String> generateStateHolder() {
            int totalBits = totalBitsNeeded();
            boolean isLong = totalBits > 32;

            boolean changeSupport = utils.annotationValue(on, "generateChangeSupport", Boolean.class, false);

            String stateName = el.getSimpleName() + "State";
            ClassBuilder<String> result = ClassBuilder.forPackage(utils.packageName(el))
                    .named(el.getSimpleName() + "StateHolder")
                    .docComment("An atomic wrapper around a " + stateName + ".")
                    .withModifier(PUBLIC, FINAL);

            if (changeSupport) {
                result.importing(Supplier.class);
                result.field("listener", fld -> {
                    fld.withModifier(PRIVATE, FINAL)
                            .ofType(stateName + "Listener");
                });
                result.field("getter", fld -> {
                    fld.withModifier(PRIVATE, FINAL)
                            .initializedTo("this::state")
                            .ofType("Supplier<" + stateName + ">");
                });

                result.innerClass("Holder", sh -> {
                    sh.docComment("Holds the old state computed within the lambda for updateAndGet, so that "
                            + "we have a previous state to compare with to decide if the listener should be "
                            + "notified.  The <code>set()</code> method may be called multiple times under "
                            + "contention.")
                            .withModifier(PRIVATE, STATIC, FINAL)
                            .field("changedState", cs -> {
                                cs.withModifier(PRIVATE)
                                        .ofType(stateName);
                            })
                            .method("set", set -> {
                                set.addArgument(stateName, "newState")
                                        .returning(stateName)
                                        .body(bb -> {
                                            bb.lineComment("Note that, due to the way atomic updateAndGet methods function,")
                                                    .lineComment("this method may be called multiple times");
                                            bb.assertingNotNull("newState");
                                            bb.ifNull("newState")
                                                    .andThrow(nb -> {
                                                        nb.withStringLiteral("New state may not be null")
                                                                .ofType("IllegalArgumentException");
                                                    }).endIf();
                                            bb.assign("changedState").toExpression("newState");
                                            bb.returning("newState");
                                        });
                            })
                            .method("get", get -> {
                                get.returning(stateName)
                                        .body(bb -> {
                                            bb.ifNull("changedState")
                                                    .andThrow(nb -> {
                                                        nb.withStringLiteral("New state was not set")
                                                                .ofType("IllegalStateException");
                                                    }).endIf();

                                            bb.returning("changedState");
                                        });
                            });
                });

            }

            String atomicType = isLong ? "AtomicLong" : "AtomicInteger";
            result.importing(UnaryOperator.class);
            if (isLong) {
                result.importing(AtomicLong.class);
            } else {
                result.importing(AtomicInteger.class);
            }

            String stateMethod = "new" + stateName;

            result.importing("static " + result.packageName() + "." + stateName + "." + stateMethod);

            result.field("state").withModifier(PRIVATE, FINAL)
                    .initializedWithNew(nb
                            -> nb.ofType(atomicType))
                    .ofType(atomicType);

            result.method("state", mth -> {
                mth.withModifier(PUBLIC)
                        .docComment("Get the current state.\n@return the state")
                        .returning(stateName)
                        .body(bb -> {
                            bb.returningInvocationOf(stateMethod)
                                    .withArgumentFromInvoking("get")
                                    .on("state").inScope();
                        });
            });
            String valueMethod = isLong ? "getAsLong" : "getAsInt";

            if (changeSupport) {
                result.method("updateAndGetNoListener", mth -> {
                    mth.withModifier(PRIVATE)
                            .addArgument("UnaryOperator<" + stateName + ">", "transition")
                            .returning(stateName)
                            .body(bb -> {
                                bb.lineComment("Separating this into another method gives it a better chance")
                                        .lineComment("of being inlined by the compiler");
                                bb.declare("result")
                                        .initializedByInvoking("updateAndGet")
                                        .withLambdaArgument(lb -> {
                                            lb.withArgument("old")
                                                    .body(lbb -> {
                                                        lbb.returningInvocationOf(valueMethod)
                                                                .onInvocationOf("apply")
                                                                .withArgumentFromInvoking(stateMethod)
                                                                .withArgument("old")
                                                                .inScope()
                                                                .on("transition");
                                                    });
                                        }).on("state").as(isLong ? "long" : "int");
                                bb.returningInvocationOf(stateMethod).withArgument("result").inScope();
                            });
                });
            }

            result.method("updateAndGet", mth -> {
                mth.withModifier(PUBLIC)
                        .addArgument("UnaryOperator<" + stateName + ">", "transition")
                        .returning(stateName)
                        .docComment("Update the state, applying the passed UnaryOperator"
                                + " and returning the new value."
                                + "\nNote that, per the contract of AtomicInteger/AtomicLong, "
                                + "the passed unary operator may be called more than once, and must be stateless, itself."
                                + "\n@param transition A UnaryOperator that computes a new state given an old one."
                                + "\n@return the new state")
                        .body(bb -> {
                            if (changeSupport) {
                                ClassBuilder.IfBuilder<?> noHolder = bb.ifNull("listener");
                                noHolder.returningInvocationOf("updateAndGetNoListener")
                                        .withArgument("transition")
                                        .inScope().endIf();
                            }
                            if (changeSupport) {
                                bb.declare("holder")
                                        .initializedWith("new Holder()")
                                        .as("Holder");
                            }
                            bb.declare("result")
                                    .initializedByInvoking("updateAndGet")
                                    .withLambdaArgument(lb -> {
                                        lb.withArgument("old")
                                                .body(lbb -> {
                                                    if (changeSupport) {
                                                        lbb.returningInvocationOf(valueMethod)
                                                                .onInvocationOf("apply")
                                                                .withArgumentFromInvoking("set")
                                                                .withArgumentFromInvoking(stateMethod)
                                                                .withArgument("old")
                                                                .inScope()
                                                                .on("holder")
                                                                .on("transition");

                                                    } else {
                                                        lbb.returningInvocationOf(valueMethod)
                                                                .onInvocationOf("apply")
                                                                .withArgumentFromInvoking(stateMethod)
                                                                .withArgument("old")
                                                                .inScope()
                                                                .on("transition");
                                                    }
                                                });
                                    }).on("state").as(isLong ? "long" : "int");
                            if (changeSupport) {
                                bb.declare("newValue")
                                        .initializedByInvoking(stateMethod)
                                        .withArgument("result")
                                        .inScope().as(stateName);
                                bb.iff().booleanExpression("!newValue.equals(holder.get())")
                                        .invoke("onChange")
                                        .withArgumentFromInvoking("get").on("holder")
                                        .withArgument("newValue")
                                        .withArgument("getter")
                                        .on("listener")
                                        .endIf();
                                bb.returning("newValue");
                            } else {
                                bb.returningInvocationOf(stateMethod).withArgument("result").inScope();
                            }
                        });
            });
            result.method("getAndUpdate", mth -> {
                mth.withModifier(PUBLIC)
                        .addArgument("UnaryOperator<" + stateName + ">", "transition")
                        .docComment("Update the state, applying the passed UnaryOperator"
                                + " and returning the <i>old</i> value."
                                + "\nNote that, per the contract of AtomicInteger/AtomicLong, "
                                + "the passed unary operator may be called more than once, and must be stateless, itself."
                                + "\n@param transition A UnaryOperator that computes a new state given an old one."
                                + "\n@return the previous value")
                        .returning(stateName)
                        .body(bb -> {
                            bb.declare("result")
                                    .initializedByInvoking("getAndUpdate")
                                    .withLambdaArgument(lb -> {
                                        lb.withArgument("old")
                                                .body(lbb -> {
                                                    lbb.returningInvocationOf(valueMethod)
                                                            .onInvocationOf("apply")
                                                            .withArgumentFromInvoking(stateMethod)
                                                            .withArgument("old")
                                                            .inScope()
                                                            .on("transition");
                                                });
                                    }).on("state").as(isLong ? "long" : "int");
                            if (changeSupport) {
                                bb.iff(iff -> {
                                    ClassBuilder.IfBuilder<?> ib = iff.isNotNull("listener")
                                            .endCondition();
                                    ib.declare("oldState")
                                            .initializedByInvoking(stateMethod)
                                            .withArgument("result")
                                            .inScope()
                                            .as(stateName);
                                    ib.declare("newState")
                                            .initializedByInvoking("apply")
                                            .withArgument("oldState")
                                            .on("transition")
                                            .as(stateName);
                                    ClassBuilder.IfBuilder<?> nif = ib.iff().booleanExpression("!oldState.equals(newState)");
                                    nif.invoke("onChange")
                                            .withArgument("oldState")
                                            .withArgument("newState")
                                            .withArgument("getter")
                                            .on("listener")
                                            .endIf();
                                    ib.returning("oldState").endIf();
                                });
                            }

                            bb.returningInvocationOf(stateMethod)
                                    .withArgument("result").inScope();
                        });
            });

            result.overridePublic("toString").returning("String")
                    .body().returningInvocationOf("toString")
                    .onInvocationOf("state").inScope().endBlock();

            result.constructor(con -> {
                con.setModifier(PUBLIC);
                if (changeSupport) {
                    con.docComment("Create a new " + result.className() + " with the default (0) initial state.")
                            .body(bb -> {
                                bb.assign("this.listener").toExpression("null");
                            });
                } else {
                    con.docComment("Create a new " + result.className() + " with the default (0) initial state.")
                            .body().endBlock();
                }
            });
            result.constructor(con -> {
                con.docComment("Create a new " + result.className() + " with the passed initial state."
                        + "\n@param initialState the initial state");
                con.setModifier(PUBLIC)
                        .addArgument(stateName, "initialState")
                        .body(bb -> {
                            bb.invoke("set")
                                    .withArgumentFromInvoking(valueMethod)
                                    .on("initialState")
                                    .on("state");
                            if (changeSupport) {
                                bb.assign("this.listener").toExpression("null");
                            }
                        });
            });

            if (changeSupport) {
                result.constructor(con -> {
                    con.setModifier(PUBLIC)
                            .addArgument(stateName + "Listener", "listener");
                    con.docComment("Create a new " + result.className() + " with the default (0) initial state"
                            + " notifying changes to the passed Listener.\n"
                            + "@param listener a listener")
                            .body(bb -> {
                                bb.assign("this.listener").toExpression("listener");
                            });
                });
                result.constructor(con -> {
                    con.docComment("Create a new " + result.className() + " with the passed initial state, "
                            + "and notifying changes to the passed listener."
                            + "\n@param initialState the initial state"
                            + "\n@param listener a listener")
                            .setModifier(PUBLIC)
                            .addArgument(stateName, "initialState")
                            .addArgument(stateName + "Listener", "listener")
                            .body(bb -> {
                                bb.invoke("set")
                                        .withArgumentFromInvoking(valueMethod)
                                        .on("initialState")
                                        .on("state");
                                bb.assign("this.listener").toExpression("listener");
                            });
                });

            }

            result.method("set", mth -> {
                mth.withModifier(PUBLIC)
                        .docComment("Replace the current state with the passed "
                                + "value\n@return true if the new value differed from the old"
                                + "\n@throws IllegalArgumentException if the passed state is null")
                        .addArgument(stateName, "newState")
                        .returning("boolean")
                        .body(bb -> {
                            bb.ifNull("newState")
                                    .andThrow(nb -> {
                                        nb.withStringLiteral("New state may not be null.")
                                                .ofType("IllegalArgumentException");
                                    }).endIf();
                            bb.declare("oldState").initializedByInvoking("getAndUpdate")
                                    .withLambdaArgument(lb -> {
                                        lb.withArgument("old")
                                                .body().returning("newState").endBlock();
                                    }).inScope()
                                    .as(stateName);
                            if (changeSupport) {
                                bb.declare("unchanged")
                                        .initializedByInvoking("equals")
                                        .withArgument("newState")
                                        .on("oldState")
                                        .as("boolean");

                                bb.iff().booleanExpression("!unchanged && listener != null")
                                        .invoke("onChange")
                                        .withArgument("oldState")
                                        .withArgument("newState")
                                        .withArgument("getter")
                                        .on("listener")
                                        .endIf();

                                bb.returning("!unchanged");
                            } else {
                                bb.statement("return !newState.equals(oldState)");
                            }
                        });
            });

            return result;
        }

        ClassBuilder<String> generator() {
            int totalBits = totalBitsNeeded();
            boolean isLong = totalBits > 32;
            String valueType = isLong ? "long" : "int";
            int total = totalBitsNeeded();

            ClassBuilder<String> result = ClassBuilder.forPackage(utils.packageName(el))
                    .named(el.getSimpleName() + "State")
                    .importing(Serializable.class)
                    .implementing("Serializable")
                    //                    .generateDebugLogCode()
                    .withModifier(PUBLIC, FINAL)
                    .implementing(el.getSimpleName().toString())
                    .docComment("Wraps a single " + valueType
                            + " as an implementation of " + el.getSimpleName()
                            + ", using " + totalBits + " bits."
                    )
                    .field("value", fld -> {
                        fld.withModifier(PRIVATE, FINAL)
                                .ofType(valueType);
                    })
                    .field("serialVersionUid", fld -> {
                        fld.withModifier(PRIVATE, STATIC, FINAL)
                                .initializedWith(serialVersionUid());
                    })
                    .constructor(con -> {
                        con.setModifier(PUBLIC)
                                .addArgument(valueType, "value")
                                .body(bb -> {
                                    bb.invoke("validate").withArgument("value").inScope();
                                    bb.statement("this.value = value");
                                });
                    });

            if (isLong) {
                result.importing(LongSupplier.class);
                result.implementing("LongSupplier");
                result.overridePublic("getAsLong")
                        .returning("long")
                        .bodyReturning("value");
            } else {
                result.importing(IntSupplier.class);
                result.implementing("IntSupplier");
                result.overridePublic("getAsInt")
                        .returning("int")
                        .bodyReturning("value");
            }

            result.field("INITIAL")
                    .withModifier(PUBLIC, STATIC, FINAL)
                    .initializedWithNew(nb -> {
                        nb.withArgument(0)
                                .ofType(result.className());
                    }).ofType(result.className());

            result.method("new" + result.className())
                    .docComment("Create a new " + result.className() + "."
                            + "\n@param the initial value"
                            + "\n@return A " + result.className()
                            + "\n@throws IllegalArgumentException if the passed value is "
                            + "outside the bounds of the possible values of "
                            + result.className())
                    .withModifier(PUBLIC, STATIC)
                    .addArgument(valueType, "initialValue")
                    .returning(result.className())
                    .body().returningNew().withArgument("initialValue")
                    .ofType(result.className());

            boolean invMaskIsZero;
            if (isLong) {
                long allMask = 0;
                for (int i = 0; i < total; i++) {
                    allMask |= 1L << i;
                }
                invMaskIsZero = ~allMask == 0;
                if (!invMaskIsZero) {
                    result.field("ALL_INV_MASK")
                            .docComment("" + allMask + " inv " + (~allMask) + " bits " + totalBits)
                            .withModifier(FINAL, PRIVATE, STATIC)
                            .initializedTo(asBinaryString(~allMask))
                            .ofType("long");
                }
            } else {
                int allMask = 0;
                for (int i = 0; i < total; i++) {
                    allMask |= 1 << i;
                }
                invMaskIsZero = ~allMask == 0;
                if (!invMaskIsZero) {
                    result.field("ALL_INV_MASK")
                            .withModifier(FINAL, PRIVATE, STATIC)
                            .initializedTo(asBinaryString(~allMask))
                            .ofType("int");
                }
            }

            result.method("validate", mth -> {
                mth.docComment("Ensures that the passed value is valid."
                        + "\n@param value The value a " + result.className() + " is "
                        + "being constructed for"
                        + "\n@throws IllegalArgumentException if the value is outside the "
                        + "bounds of the possible values of " + result.className()
                );
                mth.withModifier(PRIVATE, STATIC)
                        .addArgument(valueType, "value")
                        .body(bb -> {
                            if (!invMaskIsZero) {
                                bb.iff().booleanExpression("(value & ALL_INV_MASK) != 0")
                                        .andThrow(nb -> {
                                            nb.withStringConcatentationArgument("Value contains "
                                                    + "set bits which must not be set in a " + result.className() + ": ")
                                                    .appendInvocationOf("toBinaryString")
                                                    .withArgument("value & ALL_INV_MASK")
                                                    .on("Long")
                                                    .endConcatenation()
                                                    .ofType("IllegalArgumentException");
                                        }).endIf();
                            }
                            toElements().forEach(el -> el.contributeValidationClause(isLong, bb));
                        });
            });

            result.overridePublic("hashCode", hc -> {
                hc.returning("int").
                        body(bb -> {
                            if (isLong) {
                                bb.returning("(int) (102071L * (value ^ (value >>> 32)))");
                            } else {
                                bb.returning("43867 * value");
                            }
                        });
            });

            result.overridePublic("equals")
                    .addArgument("Object", "o")
                    .returning("boolean")
                    .body(bb -> {
                        bb.iff().booleanExpression("o == this")
                                .returning(true)
                                .elseIf().booleanExpression("o == null || o.getClass() != " + result.className() + ".class")
                                .returning(false)
                                .endIf();
                        bb.returning("((" + result.className() + ") o).value == value");
                    });

            result.overridePublic("toString", ts -> {
                ts.returning("String")
                        .body(bb -> {
                            bb.returningStringConcatenation(result.className() + "(", concat -> {
                                int ct = 0;
                                for (BitsElement el : toElements()) {
                                    el.generateReadMethod(isLong, result);
                                    el.generateWriteMethod(isLong, result);
                                    if (ct++ > 0) {
                                        concat.append(", ");
                                    }
                                    concat.append(el.name + "=")
                                            .appendInvocationOf(el.name)
                                            .inScope();
                                }

                                concat.append(") = 0b")
                                        .appendInvocationOf("toBinaryString")
                                        .withArgument("value")
                                        .on(isLong ? "Long" : "Integer");

                                concat.append(" = 0x")
                                        .appendInvocationOf("toHexString")
                                        .withArgument("value")
                                        .on(isLong ? "Long" : "Integer");

                                concat.append(" = ")
                                        .appendInvocationOf("toString")
                                        .withArgument("value")
                                        .on(isLong ? "Long" : "Integer");

                                concat.endConcatenation();
                            });
                        });
            });

            return result;
        }

        public List<BitsElement> toElements() {
            List<BitsElement> result = new ArrayList<>();
            int currBit = 0;
            for (Map.Entry<String, ExecutableElement> e : methodForName.entrySet()) {
                int needed = bitsNeeded(e.getValue());
                BitsElement be = new BitsElement(currBit, needed,
                        e.getKey(), e.getValue(), on, isEnum(utils.erasureOf(e.getValue().getReturnType())));
                result.add(be);
                currBit += needed;
            }
            return result;
        }

        public void addItem(String name, ExecutableElement ee) {
            methodForName.put(name, ee);
            validate();
        }

        void validate() {
            if (totalBitsNeeded() > 64) {
                utils.fail("Atomic state requires more than 64 bits - cannot generate");
            }
        }

        int totalBitsNeeded() {
            int result = 0;
            for (ExecutableElement ee : methodForName.values()) {
                result += bitsNeeded(ee);
            }
            return result;
        }

    }

    int bitsNeeded(ExecutableElement ee) {
        TypeMirror mir = ee.getReturnType();
        Optional<ValueRangeProxy> vr = valueRange(ee);
        if (vr.isPresent()) {
            switch (mir.getKind()) {
                case BOOLEAN:
                case DOUBLE:
                case FLOAT:
                    utils.fail("Cannot use ValueRange on " + mir.getKind());
                    break;
                case INT:
                case LONG:
                case BYTE:
                case SHORT:
                    return vr.get().bitsRequired();
            }
        }

        switch (mir.getKind()) {
            case BOOLEAN:
                return 1;
            case BYTE:
                return 8;
            case FLOAT:
                return 32;
            case SHORT:
            case CHAR:
                return 16;
            case INT:
                return 32;
            case LONG:
                return 64;
            case DOUBLE:
                return 64;
            default:
                if (isEnum(mir)) {
                    int constCount = countEnumMembers(mir);

                    return bitsFor(constCount);
                }
                return 1000;
        }
    }

    private int countEnumMembers(TypeMirror type) {
        TypeElement el = utils.processingEnv().getElementUtils().getTypeElement(type.toString());
        if (el == null) {
            utils.fail("Unable to resolve " + type);
            return 0;
        }
        int ct = 0;
        for (Element to : el.getEnclosedElements()) {
            switch (to.getKind()) {
                case ENUM_CONSTANT:
                    ct++;
            }
        }
        return ct;
    }

    private List<String> getEnumMembers(ExecutableElement el) {
        return getEnumMembers(el.getReturnType());
    }

    private List<String> getEnumMembers(TypeMirror type) {
        TypeElement el = utils.processingEnv().getElementUtils().getTypeElement(type.toString());
        if (el == null) {
            utils.fail("Unable to resolve " + type);
            return emptyList();
        }
        List<String> result = new ArrayList<>();
        int ct = 0;
        for (Element to : el.getEnclosedElements()) {
            switch (to.getKind()) {
                case ENUM_CONSTANT:
                    result.add(to.getSimpleName().toString());
            }
        }
        return result;

    }

    boolean isEnum(TypeMirror mir) {
        return utils.isAssignable(mir, enumType());
    }

    static int bitsFor(int count) {
        int result = Long.numberOfTrailingZeros(
                nearestPowerOfTwoLessThan(count)
        ) + 1;
        return result;
    }

    static int bitsFor(long count) {
        int result = Long.numberOfTrailingZeros(
                nearestPowerOfTwoLessThan(count)
        ) + 1;
        return result;
    }

    static long nearestPowerOfTwoLessThan(long count) {
        double pow = floor(log(count) / log(2));
        return (long) Math.pow(2, pow);
    }

    private static String asBinaryString(int value) {
        if (value == 0) {
            return "0";
        }
        return "0b" + Integer.toBinaryString(value);
    }

    private static String asBinaryString(long value) {
        if (value == 0L) {
            return "0LÏ";
        }
        return "0b" + Long.toBinaryString(value) + "L";
    }

    static long maxValueOf(TypeKind kind) {
        switch (kind) {
            case BOOLEAN:
                return 1;
            case BYTE:
                return Byte.MAX_VALUE;
            case CHAR:
                return Character.MAX_VALUE;
            case INT:
                return Integer.MAX_VALUE;
            case FLOAT:
                return (long) Math.floor(Float.MAX_VALUE);
            case DOUBLE:
                return (long) Math.floor(Double.MAX_VALUE);
            case SHORT:
                return Short.MAX_VALUE;
            case LONG:
            default:
                return Long.MAX_VALUE;
        }
    }

    static long minValueOf(TypeKind kind) {
        switch (kind) {
            case BOOLEAN:
                return 0;
            case BYTE:
                return Byte.MIN_VALUE;
            case CHAR:
                return Character.MIN_VALUE;
            case INT:
                return Integer.MIN_VALUE;
            case FLOAT:
                return (long) Math.floor(Float.MIN_VALUE);
            case DOUBLE:
                return (long) Math.floor(Double.MIN_VALUE);
            case SHORT:
                return Short.MIN_VALUE;
            case LONG:
            default:
                return Long.MIN_VALUE;
        }
    }

    static TypeKind fitting(int bits) {
        if (bits <= 8) {
            return TypeKind.BYTE;
        } else if (bits <= 16) {
            return TypeKind.SHORT;
        } else if (bits <= 32) {
            return TypeKind.INT;
        } else if (bits <= 64) {
            return TypeKind.LONG;
        }
        return TypeKind.NONE;
    }

    static String primitiveTypeName(TypeKind kind) {
        switch (kind) {
            case BOOLEAN:
                return "boolean";
            case BYTE:
                return "byte";
            case INT:
                return "int";
            case SHORT:
                return "short";
            case LONG:
            default:
                return "long";
        }
    }

    static String primitiveTypeCast(TypeKind kind) {
        switch (kind) {
            case BYTE:
            case SHORT:
            case INT:
                return "(" + primitiveTypeName(kind) + ")";
            default:
                return "";
        }
    }

    static String primitiveTypeFitting(int bits) {
        return primitiveTypeName(fitting(bits));
    }

    static String format(TypeKind kind, long value) {
        String c = primitiveTypeName(kind);
        if (!c.isEmpty()) {
            return "(" + c + ") " + Long.toString(value);
        }
        if (kind == TypeKind.LONG) {
            return Long.toString(value) + "L";
        }
        return Long.toString(value);
    }

    Optional<ValueRangeProxy> valueRange(ExecutableElement el) {
        AnnotationMirror mir = utils.findMirror(el, VALUE_RANGE_ANNO);
        if (mir != null) {
            return Optional.of(new ValueRangeProxy(mir, el));
        }
        return Optional.empty();
    }

    class ValueRangeProxy {

        private final long min;
        private final long max;
        private final TypeKind kind;

        ValueRangeProxy(AnnotationMirror mir, ExecutableElement el) {
            TypeMirror returnType = el.getReturnType();
            kind = returnType.getKind();
            long minLocal = utils.annotationValue(mir, "minimum", Long.class, Long.MAX_VALUE);
            long maxLocal = utils.annotationValue(mir, "maximum", Long.class, Long.MIN_VALUE);

            if (minLocal == Long.MAX_VALUE) {
                minLocal = minValueOf(kind);
            }
            if (maxLocal == Long.MIN_VALUE) {
                maxLocal = maxValueOf(kind);
            }
            this.min = minLocal;
            this.max = maxLocal;
            if (min >= max) {
                utils.fail("Minimum and maximum are contradictory: " + min + " and " + max, el, mir);
            }
        }

        String typeCast() {
            switch (kind) {
                case SHORT:
                    return "(short)";
                case BYTE:
                    return "(byte)";
                default:
                    return "";
            }
        }

        private String maybeSuffix(long value) {
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                return Long.toString(value) + "L";
            } else {
                return Long.toString(value);
            }
        }

        String minFieldNameOf(String fieldName) {
            return fieldName.toUpperCase() + "_MIN";
        }

        String maxFieldNameOf(String fieldName) {
            return fieldName.toUpperCase() + "_MAX";
        }

        private <T> void generateField(int ofBits, String fieldName, boolean isLong, long value, ClassBuilder<T> cb) {
            cb.field(fieldName, fld -> {
                if (isLong) {
                    fld.withModifier(PRIVATE, STATIC, FINAL)
                            .initializedWith(value);
                } else {
                    fld.withModifier(PRIVATE, STATIC, FINAL)
                            .initializedWith((int) value);
                }
            });
        }

        <T> void generateFields(String fieldName, boolean isLong, ClassBuilder<T> cb) {
            generateField(bitsFor(min), minFieldNameOf(fieldName), isLong, min, cb);
            generateField(bitsFor(max), maxFieldNameOf(fieldName), isLong, max, cb);
        }

        <T> void generateValidationTest(String fieldName, String varName, BlockBuilder<T> bb) {
            bb.lineComment("Validate " + fieldName + " as " + varName);
            bb.lineComment("Min " + min + " max " + max);
            bb.iff().booleanExpression(varName + " < " + minFieldNameOf(fieldName) + " || "
                    + varName + " > " + maxFieldNameOf(fieldName))
                    .andThrow(nb -> {
                        nb.withStringConcatentationArgument(fieldName)
                                .append(" must be >= ")
                                .appendExpression(minFieldNameOf(fieldName))
                                .append(" and <= ")
                                .appendExpression(maxFieldNameOf(fieldName))
                                .append(" but got ")
                                .appendExpression(varName)
                                .endConcatenation()
                                .ofType("IllegalArgumentException");
                    }).endIf();
        }

        String toStorableValue(String fieldName, String varName, boolean isLong) {
            return (isLong ? "(long)" : "(int)")
                    + " (" + varName + " - " + minFieldNameOf(fieldName) + ")";
        }

        String toReturnableValue(String fieldName, String varName, boolean varIsLong) {
            String cast = primitiveTypeCast(kind);
            String result = varName + " + " + format(kind, min);
            if (!cast.isEmpty()) {
                result = cast + " (" + result + ")";
            }
            return result;
        }

        int bitsRequired() {
            return bitsFor(max - min);
        }
    }

}
