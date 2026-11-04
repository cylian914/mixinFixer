package tess.mc.mixinFixer.mixin;

import com.bawnorton.mixinsquared.adjuster.tools.*;
import com.bawnorton.mixinsquared.adjuster.tools.type.MethodListAnnotationNode;
import com.bawnorton.mixinsquared.api.MixinAnnotationAdjuster;
import com.mojang.logging.LogUtils;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.transformer.ClassInfo;
import tess.mc.mixinFixer.util.asmutil.Asmutil;
import tess.mc.mixinFixer.util.asmutil.InsnListBuilder;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class FixerAnnotationAdjuster implements MixinAnnotationAdjuster {
    @Override
    public AdjustableAnnotationNode adjust(List<String> targetClassNames, String mixinClassName, MethodNode handlerNode, AdjustableAnnotationNode annotationNode) {
        ClassInfo ci = ClassInfo.fromCache(mixinClassName);
        ClassInfo.Method method = ci.findMethod(handlerNode);
        Map<String, ClassInfo.Method> toInjectMethod = null;
        if (annotationNode instanceof MethodListAnnotationNode mad) {
            Map<String, List<String>> methodNameAndDescMap = new HashMap<>();
            for (String methodAndMaybeDesc : mad.getMethod()) {
                if (methodAndMaybeDesc.contains("(")) {
                    int index = methodAndMaybeDesc.indexOf("(");
                    methodNameAndDescMap.computeIfAbsent(methodAndMaybeDesc.substring(0, index), k -> new ArrayList<>())
                            .add(methodAndMaybeDesc.substring(index));
                } else {
                    methodNameAndDescMap.put(methodAndMaybeDesc, null);
                }
            }
            toInjectMethod = targetClassNames.stream().map(ClassInfo::fromCache).map((c) -> {
                Optional<ClassInfo.Method> m = c.getMethods().stream().filter((cl) -> {
                    if (methodNameAndDescMap.containsKey(cl.getName())) {
                        List<String> descs = methodNameAndDescMap.get(cl.getName());
                        if (descs == null)
                            return true;
                        for (String desc : descs) {
                            if (desc.equals(cl.getDesc()))
                                return true;
                        }
                    }
                    return false;
                }).findFirst();
                return m.map(value -> new AbstractMap.SimpleImmutableEntry<>(c.getName().replace("/", "."), value)).orElse(null);
            }).filter(Objects::nonNull).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        Data data = new Data(targetClassNames, mixinClassName, handlerNode, annotationNode, ci, method, toInjectMethod);
        handle(data);
        method.remapTo(data.handlerNode.desc);
        return data.annotationNode;
    }

    public void handle(Data data) {
        switch (data.annotationNode) {
            case AdjustableModifyConstantNode n -> handleConstantNode(data, n);
            case AdjustableRedirectNode n -> handleRedirectNode(data, n);
            case AdjustableOverwriteNode n -> handleOverwrite(data, n);
            default -> {}
        }
    }

    public void handleConstantNode(Data data, AdjustableModifyConstantNode originalNode) {
        AdjustableModifyExpressionValueNode mde = AdjustableModifyExpressionValueNode.defaultNode(originalNode.getMethod(), List.of());
        mde.applyRefmap();
        AtomicBoolean returnOriginal = new AtomicBoolean(false);
        mde.withAt((at) -> {
            for (AdjustableConstantNode constant: originalNode.getConstant()) {
                int ordinal = constant.getOrdinal();
                at.add(AdjustableAtNode.defaultNode(AdjustableAtNode.InjectionPoint.CONSTANT).withArgs((args) -> {
                    boolean skipNext = false;
                    StringBuilder currentString = new StringBuilder();
                    for (Object obj : constant.values) {
                        if (skipNext) {
                            skipNext = false;
                            continue;
                        }
                        if ((currentString.isEmpty()) && obj instanceof String str) {
                            if (str.equals("ordinal"))
                                skipNext = true;
                            else
                                currentString.append(str);
                            continue;
                        }
                        if (!currentString.isEmpty()) {
                            currentString.append("=");
                            currentString.append(obj);
                            args.add(currentString.toString());
                            currentString = new StringBuilder();
                            continue;
                        }
                    }
                    if (args.isEmpty()) {
                        returnOriginal.set(true);
                    }
                    return args;
                }).withOrdinal((o) -> ordinal));
            }
            return at;
        });
        if (!returnOriginal.get())
            data.annotationNode = mde;
    }

    public void handleRedirectNode(Data data, AdjustableRedirectNode originalNode) {
        List<MethodInsnNode> indexes = new ArrayList<>();
        String mixinClassNameAsPath = data.mixinClassName.replace(".", "/");
        for (int i = 0; i < data.handlerNode.instructions.size(); i++) {
            if (data.handlerNode.instructions.get(i) instanceof MethodInsnNode methodNode) {
                StringBuilder sb = new StringBuilder("L");
                if (methodNode.owner.equals(mixinClassNameAsPath)) {
                    ClassInfo.Method m = data.classinfo.findMethod(methodNode.name, methodNode.desc, ClassInfo.INCLUDE_ALL);
                    if (m != null && m.isInjected()) {
                        sb.append(originalNode.getAt().getTarget(),
                                1, originalNode.getAt().getTarget().indexOf(';')); //calls to a shadowed function, replacing owner by target's owner
                    } else
                        sb.append(methodNode.owner);
                } else
                    sb.append(methodNode.owner);
                sb.append(";");
                sb.append(methodNode.name);
                sb.append(methodNode.desc);
                if (sb.toString().equals(originalNode.getAt().getTarget())) {
                    indexes.add(methodNode);
                }
            }
        }
        if (!indexes.isEmpty()) {
            Type handlerNodeType = Type.getMethodType(data.handlerNode.desc);
            boolean isStatic = indexes.get(0).getOpcode() == Opcodes.INVOKESTATIC;

            String handlerNodeDescWithoutCapture = originalNode.getAt().getTarget().substring(originalNode.getAt().getTarget().indexOf('(') + 1);
            if (!isStatic)
                handlerNodeDescWithoutCapture = originalNode.getAt().getTarget().split(";")[0] + ";" + handlerNodeDescWithoutCapture;
            handlerNodeDescWithoutCapture = "(" + handlerNodeDescWithoutCapture;
            Type handlerNodeDescWithoutCaptureType = Type.getMethodType(handlerNodeDescWithoutCapture);

            String redirectedMethodDesc = originalNode.getAt().getTarget().substring(originalNode.getAt().getTarget().indexOf('('));
            Type redirectedMethodType = Type.getMethodType(redirectedMethodDesc);

            boolean[] is64b = new boolean[handlerNodeDescWithoutCaptureType.getArgumentTypes().length];
            is64b[0] = false;
            for (int i = 0; i < handlerNodeDescWithoutCaptureType.getArgumentTypes().length; i++) {
                String desc = handlerNodeDescWithoutCaptureType.getArgumentTypes()[i].getDescriptor();
                is64b[i] = Asmutil.is64b(desc.charAt(0));
            }

            //  Modify desc & signature
            String originalTypeDesc = "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;";
            String originalTypeSignature = "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation<" +
                    "L" + Asmutil.primitiveToClass(handlerNodeType.getReturnType().toString()) + ";" +
                    ">;";

            String methodParameterDesc = handlerNodeDescWithoutCapture.split("\\(")[1].split("\\)")[0];
            data.handlerNode.desc = data.handlerNode.desc.substring(0, methodParameterDesc.length() + 1) +
                    originalTypeDesc +
                    data.handlerNode.desc.substring(methodParameterDesc.length() + 1);

            int addedSignatureLength = 1;
            if (data.handlerNode.signature == null)
                data.handlerNode.signature = data.handlerNode.desc;
            else {
                String signatureCopy = data.handlerNode.signature;
                while (signatureCopy.indexOf('<') != -1 && signatureCopy.indexOf('<') < methodParameterDesc.length()) {
                    int index1 = signatureCopy.indexOf('<');
                    int index2 = signatureCopy.indexOf('>');
                    String temp = signatureCopy.substring(index1, index2);
                    addedSignatureLength += temp.length() + 1;
                    signatureCopy = signatureCopy.substring(0, index1) + signatureCopy.substring(index2 + 1);
                }
            }
            data.handlerNode.signature = data.handlerNode.signature.substring(0, methodParameterDesc.length() + addedSignatureLength) +
                    originalTypeSignature +
                    data.handlerNode.signature.substring(methodParameterDesc.length() + addedSignatureLength);

            //Add optional parameter info
            int originalParameterIndex = handlerNodeDescWithoutCaptureType.getArgumentTypes().length;
            if (data.handlerNode.parameters != null)
                data.handlerNode.parameters.add(originalParameterIndex, new ParameterNode("original", 0));


            //  Add optional localVariable
            int originalVariableIndex = Asmutil.getVariableIndexOffsetFromDesc(handlerNodeDescWithoutCaptureType.getDescriptor()) + (((data.handlerNode.access & Opcodes.ACC_STATIC) > 0)?0:1);


            if (data.handlerNode.invisibleParameterAnnotations != null) {
                List<AnnotationNode>[] invisibleParameterAnnotations = data.handlerNode.invisibleParameterAnnotations;
                data.handlerNode.invisibleParameterAnnotations = new List[handlerNodeType.getArgumentTypes().length + 1];
                data.handlerNode.invisibleParameterAnnotations[originalParameterIndex] = null;
                System.arraycopy(invisibleParameterAnnotations, 0, data.handlerNode.invisibleParameterAnnotations, 0, originalParameterIndex);
                System.arraycopy(invisibleParameterAnnotations, originalParameterIndex, data.handlerNode.invisibleParameterAnnotations, originalParameterIndex + 1, invisibleParameterAnnotations.length - originalParameterIndex);
            }

                /*if (data.handlerNode.localVariables != null) { TODO-ish, not needed to run but still better to have it
                    LocalVariableNode firstParameter = null;
                    for (int i = 0; i < data.handlerNode.localVariables.size(); i++) {
                        if (Objects.equals(data.handlerNode.localVariables.get(i).name, data.handlerNode.parameters.getFirst().name)) {
                            firstParameter = data.handlerNode.localVariables.get(i);
                            break;
                        }
                    }

                    LocalVariableNode previousVariable = null;
                    for (int i = 0; i < data.handlerNode.localVariables.size(); i++) {
                        if (Objects.equals(data.handlerNode.localVariables.get(i).name, data.handlerNode.parameters.get(handlerNodeDescWithoutCaptureType.getArgumentCount() - 1).name)) {
                            previousVariable = data.handlerNode.localVariables.get(i);
                            break;
                        }
                    }
                    LocalVariableNode originalVariable = new LocalVariableNode("original",
                            "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;",
                            originalTypeSignature, firstParameter.start,
                            firstParameter.end,
                            previousVariable.index + ((previousVariable.desc.charAt(0) == 'D' || previousVariable.desc.charAt(0) == 'J') ? 2 : 1));
                    if (originalVariableIndex > data.handlerNode.localVariables.size())
                        data.handlerNode.localVariables.add(originalVariable);
                    else
                        data.handlerNode.localVariables.add(originalVariableIndex, originalVariable);

                    if (data.handlerNode.invisibleParameterAnnotations != null) {
                        List<AnnotationNode>[] invisibleParameterAnnotations = data.handlerNode.invisibleParameterAnnotations;
                        data.handlerNode.invisibleParameterAnnotations = new List[data.handlerNode.parameters.size()];
                        data.handlerNode.invisibleParameterAnnotations[originalParameterIndex] = null;
                        System.arraycopy(invisibleParameterAnnotations, 0, data.handlerNode.invisibleParameterAnnotations, 0, originalParameterIndex);
                        System.arraycopy(invisibleParameterAnnotations, originalParameterIndex, data.handlerNode.invisibleParameterAnnotations, originalParameterIndex + 1, invisibleParameterAnnotations.length - originalParameterIndex);
                    }

                    for (int i = 0; i < originalVariableIndex; i++) {
                        if (data.handlerNode.localVariables.get(i).index >= originalVariable.index)
                            data.handlerNode.localVariables.get(i).index++;
                    }
                    for (int i = originalVariableIndex + 1; i < data.handlerNode.localVariables.size(); i++) {
                        if (data.handlerNode.localVariables.get(i).index >= originalVariable.index)
                            data.handlerNode.localVariables.get(i).index++;
                    }
                }*/

            for (int i = 0; i < data.handlerNode.instructions.size(); i++) {
                if (data.handlerNode.instructions.get(i) instanceof VarInsnNode varNode && varNode.var >= originalVariableIndex)
                    varNode.var++;
            }

            // Inject instruction
            for (MethodInsnNode ins : indexes) {
                // generate instruction each time because you can't copy it easily
                InsnListBuilder ilb = new InsnListBuilder(new InsnList())
                        .iconst(handlerNodeDescWithoutCaptureType.getArgumentTypes().length)
                        .newObjectArray();
                for (int i = 0; i < handlerNodeDescWithoutCaptureType.getArgumentTypes().length; i++) {
                    if (is64b[is64b.length - 1 - i])
                        ilb.dup_x2().dup_x2();
                    else
                        ilb.dup_x1().dup_x1();
                    ilb.pop();

                    ilb.ConvertPrimitiveToObject(handlerNodeDescWithoutCaptureType.getArgumentTypes()[handlerNodeDescWithoutCaptureType.getArgumentTypes().length - 1 - i]);

                    ilb.iconst(handlerNodeDescWithoutCaptureType.getArgumentTypes().length - 1 - i);
                    ilb.dup_x1();
                    ilb.pop();
                    ilb.aastore();
                }
                ilb.loadObject(originalVariableIndex).dup_x1().pop();

                data.handlerNode.instructions.insertBefore(ins, ilb.original);

                InsnListBuilder afterCall = new InsnListBuilder(new InsnList());
                if (handlerNodeType.getReturnType().getSort() != 0) {
                    afterCall.checkCast(handlerNodeType.getReturnType())
                            .ConvertObjectToPrimitive(handlerNodeDescWithoutCaptureType.getReturnType());
                } else {
                    afterCall.pop();
                }
                data.handlerNode.instructions.insert(ins, afterCall.original);


                data.handlerNode.instructions.set(ins, new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                        "com/llamalad7/mixinextras/injector/wrapoperation/Operation",
                        "call",
                        "([Ljava/lang/Object;)Ljava/lang/Object;",
                        true));
            }
            AdjustableWrapOperationNode won = AdjustableWrapOperationNode.defaultNode(originalNode.getMethod());
            won.setAt(List.of(originalNode.getAt()));
            won.setRequire(originalNode.getRequire());
            data.annotationNode = won;
        }
    }

    public void handleOverwrite(Data data, AdjustableOverwriteNode originalNode) {
        try {
            //reduce the priority of overwrite so mixin that inject to overwritten method doesn't crash
            Field getMixinInfoField = data.classinfo.getClass().getDeclaredField("mixin");
            getMixinInfoField.setAccessible(true);
            IMixinInfo info = (IMixinInfo)getMixinInfoField.get(data.classinfo);
            if (info.getPriority() != info.getConfig().getPriority())
                return;
            Field getMixinPriorityField = info.getClass().getDeclaredField("priority");
            getMixinPriorityField.setAccessible(true);
            getMixinPriorityField.set(info, info.getPriority() - 10);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static class Data {
        public List<String> targetClassNames;
        public String mixinClassName;
        public MethodNode handlerNode;
        public AdjustableAnnotationNode annotationNode;
        public ClassInfo classinfo;
        public ClassInfo.Method classInfoMethod;
        public Map<String, ClassInfo.Method> targetMethods;

        public Data(List<String> targetClassNames, String mixinClassName, MethodNode handlerNode,
                    AdjustableAnnotationNode annotationNode, ClassInfo classinfo, ClassInfo.Method classInfoMethod, Map<String, ClassInfo.Method>targetMethods) {
            this.targetClassNames = targetClassNames;
            this.mixinClassName = mixinClassName;
            this.handlerNode = handlerNode;
            this.annotationNode = annotationNode;
            this.classinfo = classinfo;
            this.classInfoMethod = classInfoMethod;
            this.targetMethods = targetMethods;
        }


    }
}
