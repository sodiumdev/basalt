package xyz.sodiumdev.jbasalt.compiler;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;

public enum DelayedInstruction {
    OBJECT_NOT_EQUAL(Opcodes.IF_ACMPNE) {
        @Override
        public DelayedInstruction invert() {
            return OBJECT_EQUAL;
        }
    },
    OBJECT_EQUAL(Opcodes.IF_ACMPEQ) {
        @Override
        public DelayedInstruction invert() {
            return OBJECT_NOT_EQUAL;
        }
    },
    INT_NOT_EQUAL(Opcodes.IF_ICMPNE) {
        @Override
        public DelayedInstruction invert() {
            return INT_EQUAL;
        }
    },
    INT_EQUAL(Opcodes.IF_ICMPEQ) {
        @Override
        public DelayedInstruction invert() {
            return INT_NOT_EQUAL;
        }
    },
    INT_GREATER(Opcodes.IF_ICMPGT) {
        @Override
        public DelayedInstruction invert() {
            return INT_LESS_EQUAL;
        }
    },
    INT_GREATER_EQUAL(Opcodes.IF_ICMPGE) {
        @Override
        public DelayedInstruction invert() {
            return INT_GREATER_EQUAL;
        }
    },
    INT_LESS(Opcodes.IF_ICMPLT) {
        @Override
        public DelayedInstruction invert() {
            return INT_GREATER_EQUAL;
        }
    },
    INT_LESS_EQUAL(Opcodes.IF_ICMPLE) {
        @Override
        public DelayedInstruction invert() {
            return INT_GREATER;
        }
    },
    EQUAL(Opcodes.IFEQ) {
        @Override
        public DelayedInstruction invert() {
            return NOT_EQUAL;
        }
    },
    NOT_EQUAL(Opcodes.IFNE) {
        @Override
        public DelayedInstruction invert() {
            return EQUAL;
        }
    },
    GREATER_EQUAL(Opcodes.IFGE) {
        @Override
        public DelayedInstruction invert() {
            return LESS_THAN;
        }
    },
    GREATER_THAN(Opcodes.IFGT) {
        @Override
        public DelayedInstruction invert() {
            return LESS_EQUAL;
        }
    },
    LESS_EQUAL(Opcodes.IFLE) {
        @Override
        public DelayedInstruction invert() {
            return GREATER_THAN;
        }
    },
    LESS_THAN(Opcodes.IFLT) {
        @Override
        public DelayedInstruction invert() {
            return GREATER_EQUAL;
        }
    };

    private final int opcode;

    DelayedInstruction(int opcode) {
        this.opcode = opcode;
    }

    public void emitConstant(Compiler compiler) {
        LabelNode labelTrue = new LabelNode();
        LabelNode labelRet = new LabelNode();
        compiler.emit(new JumpInsnNode(this.opcode, labelTrue),
                new InsnNode(Opcodes.ICONST_0),
                new JumpInsnNode(Opcodes.GOTO, labelRet),
                labelTrue,
                new InsnNode(Opcodes.ICONST_1),
                labelRet);
    }

    public void emitJump(Compiler compiler, LabelNode labelNode) {
        compiler.emit(new JumpInsnNode(this.opcode, labelNode));
    }

    public void applyStackChanges(Compiler compiler) {
        compiler.notifyPopStack();
        compiler.notifyPopStack();
        compiler.notifyPushStack(StackTypes.BOOLEAN);
    }

    public abstract DelayedInstruction invert();
}
