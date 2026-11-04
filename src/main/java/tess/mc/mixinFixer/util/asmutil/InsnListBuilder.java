package tess.mc.mixinFixer.util.asmutil;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

public class InsnListBuilder {
    public InsnList original;

    public InsnListBuilder(InsnList original) {
        this.original = original;
    }

    public InsnListBuilder iconst(final int intValue) {
        if (intValue >= -1 && intValue <= 5) {
            original.add(new InsnNode(Opcodes.ICONST_0 + intValue));
        } else if (intValue >= Byte.MIN_VALUE && intValue <= Byte.MAX_VALUE) {
            original.add(new IntInsnNode(Opcodes.BIPUSH, intValue));
        } else if (intValue >= Short.MIN_VALUE && intValue <= Short.MAX_VALUE) {
            original.add(new IntInsnNode(Opcodes.SIPUSH, intValue));
        } else {
            original.add(new LdcInsnNode(intValue));
        }
        return this;
    }

    public InsnListBuilder checkCast(Type type) {
        original.add(new TypeInsnNode(Opcodes.CHECKCAST, Asmutil.primitiveToClass(type.toString())));
        return this;
    }

    public InsnListBuilder newObjectArray() {
        original.add(new TypeInsnNode(Opcodes.ANEWARRAY, Type.getInternalName(Object.class)));
        return this;
    }

    public InsnListBuilder dup_x1() {
        original.add(new InsnNode(Opcodes.DUP_X1));
        return this;
    }

    public InsnListBuilder dup_x2() {
        original.add(new InsnNode(Opcodes.DUP_X2));
        return this;
    }

    public InsnListBuilder pop() {
        original.add(new InsnNode(Opcodes.POP));
        return this;
    }

    public InsnListBuilder pop2() {
        original.add(new InsnNode(Opcodes.POP2));
        return this;
    }

    public InsnListBuilder aastore() {
        original.add(new InsnNode(Opcodes.AASTORE));
        return this;
    }

    public InsnListBuilder load(final int varIndex, final Type type) {
        original.add(new IntInsnNode(type.getOpcode(Opcodes.ILOAD), varIndex));
        return this;
    }

    public InsnListBuilder loadObject(final int varIndex) {
        original.add(new VarInsnNode(Opcodes.ALOAD, varIndex));
        return this;
    }

    public InsnListBuilder invokeinterface(final String owner, final String name, final String descriptor) {
        original.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, owner, name, descriptor, true));
        return this;
    }

    public InsnListBuilder invokespecial(
            final String owner, final String name, final String descriptor, final boolean isInterface) {
        original.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, name, descriptor, isInterface));
        return this;
    }

    public InsnListBuilder invokestatic(
            final String owner, final String name, final String descriptor, final boolean isInterface) {
        original.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, name, descriptor, isInterface));
        return this;
    }

    public InsnListBuilder invokeVirtual(
            final String owner, final String name, final String descriptor, final boolean isInterface) {
        original.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, name, descriptor, isInterface));
        return this;
    }

    public InsnListBuilder ConvertPrimitiveToObject(Type primitive) {
        if (primitive.getSort() >= Type.ARRAY)
            return this; //already an object...
        switch (primitive.getSort()) {
            case Type.VOID:
                break;
            case Type.BOOLEAN:
                invokestatic("java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
                break;
            case Type.CHAR:
                invokestatic("java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false);
                break;
            case Type.BYTE:
                invokestatic("java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false);
                break;
            case Type.SHORT:
                invokestatic("java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false);
                break;
            case Type.INT:
                invokestatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
                break;
            case Type.FLOAT:
                invokestatic("java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
                break;
            case Type.LONG:
                invokestatic("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
                break;
            case Type.DOUBLE:
                invokestatic("java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
                break;
        }
        return this;
    }

    public InsnListBuilder ConvertObjectToPrimitive(Type primitive) {
        if (primitive.getSort() >= Type.ARRAY)
            return this; //already an object...
        switch (primitive.getSort()) {
            case Type.VOID:
                break;
            case Type.BOOLEAN:
                invokeVirtual("java/lang/Boolean", "booleanValue", "()Z", false);
                break;
            case Type.CHAR:
                invokeVirtual("java/lang/Character", "charValue", "()C", false);
                break;
            case Type.BYTE:
                invokeVirtual("java/lang/Byte", "byteValue", "()B", false);
                break;
            case Type.SHORT:
                invokeVirtual("java/lang/Short", "shortValue", "()S", false);
                break;
            case Type.INT:
                invokeVirtual("java/lang/Integer", "intValue", "()I", false);
                break;
            case Type.FLOAT:
                invokeVirtual("java/lang/Float", "floatValue", "()F", false);
                break;
            case Type.LONG:
                invokeVirtual("java/lang/Long", "longValue", "()J", false);
                break;
            case Type.DOUBLE:
                invokeVirtual("java/lang/Double", "doubleValue", "()D", false);
                break;
        }
        return this;
    }
}
