package tess.mc.mixinFixer.util.asmutil;

import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

public class Asmutil {
    public static boolean opCodeLoad(int opCode) {
        return switch (opCode) {
            case Opcodes.ACONST_NULL, Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2,
                 Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5, Opcodes.LCONST_0, Opcodes.LCONST_1,
                 Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2, Opcodes.DCONST_0, Opcodes.DCONST_1,
                 Opcodes.BIPUSH, Opcodes.SIPUSH, Opcodes.LDC, Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD,
                 Opcodes.DLOAD, Opcodes.ALOAD -> true;
            default -> false;
        };
    }

    public static String primitiveToClass(String desc) {
        char firstChar = desc.charAt(0);
        return switch (firstChar) {
            case 'B' -> "java/lang/Byte";
            case 'C' -> "java/lang/Character";
            case 'D' -> "java/lang/Double";
            case 'F' -> "java/lang/Float";
            case 'I' -> "java/lang/Integer";
            case 'J' -> "java/lang/Long";
            case 'L' -> desc.substring(1).substring(0, desc.length() - 2);
            case 'S' -> "java/lang/Short";
            case 'Z' -> "java/lang/Boolean";
            case 'V' -> "java/lang/Void";
            default -> desc;
        };
    }

    public static boolean is64b(char desc) {
        return switch (desc) {
            case 'J', 'D' -> true;
            default -> false;
        };
    }

    public static int getVariableIndexOffsetFromDesc(String desc) {
        return getVariableIndexOffset(desc.substring(desc.indexOf('(') + 1, desc.indexOf(')')));
    }

    public static int getVariableIndexOffset(String parameter) {
        int ret = 0;
        for (String desc : splitType(parameter)) {
            ret += is64b(desc.charAt(0))?2:1;
        }
        return ret;
    }

    public static List<String> splitType(String desc) {
        int offset = 0;
        List<String> ret = new ArrayList<>();
        do {
            StringBuilder curr = new StringBuilder();
            char firstChar = desc.charAt(offset);
            while (firstChar == '[') {
                firstChar = desc.charAt(offset);
                curr.append("[");
                offset++;
            }
            switch (firstChar) {
                default:
                    curr.append(firstChar);
                    offset += 1;
                    break;
                case 'L':
                    int semicolon = desc.indexOf(';', offset);
                    int greater = desc.indexOf("<", offset);
                    if (greater != -1 && greater < semicolon) {
                        semicolon = greater;
                    }
                    String object = desc.substring(offset, semicolon + 1);
                    curr.append(object);
                    offset += object.length();
            }
            ret.add(curr.toString());
        } while (desc.length() > offset);
        return ret;
    }

    public static List<String> splitTypeIgnoreOtherChar(String desc) {
        int offset = 0;
        List<String> ret = new ArrayList<>();
        do {
            char firstChar = desc.charAt(offset);
            switch (firstChar) {
                case 'B':
                case 'C':
                case 'D':
                case 'F':
                case 'I':
                case 'J':
                case 'S':
                case 'Z':
                case 'V':
                    ret.add(String.valueOf(firstChar));
                default:
                    offset += 1;
                    break;
                case 'L':
                    String object = desc.substring(offset, desc.indexOf(';', offset) + 1);
                    ret.add(object);
                    offset += object.length();
            }
        } while (desc.length() > offset);
        return ret;
    }
}
