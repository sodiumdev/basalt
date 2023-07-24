package zip.sodium.jbasalt.compiler;

import org.objectweb.asm.Opcodes;
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
    D_NOT_EQUAL(Opcodes.IFNE, Opcodes.DCMPL) {
        @Override
        public DelayedInstruction invert() {
            return D_EQUAL;
        }
    },
    D_EQUAL(Opcodes.IFEQ, Opcodes.DCMPL) {
        @Override
        public DelayedInstruction invert() {
            return D_NOT_EQUAL;
        }
    },
    D_GREATER(Opcodes.IFGT, Opcodes.DCMPL) {
        @Override
        public DelayedInstruction invert() {
            return D_LESS_EQUAL;
        }
    },
    D_GREATER_EQUAL(Opcodes.IFGE, Opcodes.DCMPL) {
        @Override
        public DelayedInstruction invert() {
            return D_LESS;
        }
    },
    D_LESS(Opcodes.IFLT, Opcodes.DCMPL) {
        @Override
        public DelayedInstruction invert() {
            return D_GREATER_EQUAL;
        }
    },
    D_LESS_EQUAL(Opcodes.IFLE, Opcodes.DCMPL) {
        @Override
        public DelayedInstruction invert() {
            return D_GREATER;
        }
    },

    F_NOT_EQUAL(Opcodes.IFNE, Opcodes.FCMPL) {
        @Override
        public DelayedInstruction invert() {
            return F_EQUAL;
        }
    },
    F_EQUAL(Opcodes.IFEQ, Opcodes.FCMPL) {
        @Override
        public DelayedInstruction invert() {
            return F_NOT_EQUAL;
        }
    },
    F_GREATER(Opcodes.IFGT, Opcodes.FCMPL) {
        @Override
        public DelayedInstruction invert() {
            return F_LESS_EQUAL;
        }
    },
    F_GREATER_EQUAL(Opcodes.IFGE, Opcodes.FCMPL) {
        @Override
        public DelayedInstruction invert() {
            return F_LESS;
        }
    },
    F_LESS(Opcodes.IFLT, Opcodes.FCMPL) {
        @Override
        public DelayedInstruction invert() {
            return F_GREATER_EQUAL;
        }
    },
    F_LESS_EQUAL(Opcodes.IFLE, Opcodes.FCMPL) {
        @Override
        public DelayedInstruction invert() {
            return F_GREATER;
        }
    },

    NUM_NOT_EQUAL(Opcodes.IF_ICMPNE) {
        @Override
        public DelayedInstruction invert() {
            return NUM_EQUAL;
        }
    },
    NUM_EQUAL(Opcodes.IF_ICMPEQ) {
        @Override
        public DelayedInstruction invert() {
            return NUM_NOT_EQUAL;
        }
    },
    NUM_GREATER(Opcodes.IF_ICMPGT) {
        @Override
        public DelayedInstruction invert() {
            return NUM_LESS_EQUAL;
        }
    },
    NUM_GREATER_EQUAL(Opcodes.IF_ICMPGE) {
        @Override
        public DelayedInstruction invert() {
            return NUM_LESS;
        }
    },
    NUM_LESS(Opcodes.IF_ICMPLT) {
        @Override
        public DelayedInstruction invert() {
            return NUM_GREATER_EQUAL;
        }
    },
    NUM_LESS_EQUAL(Opcodes.IF_ICMPLE) {
        @Override
        public DelayedInstruction invert() {
            return NUM_GREATER;
        }
    };

    private final int opcode;
    private final Integer otherOpcode;

    DelayedInstruction(int opcode, int otherOpcode) {
        this.opcode = opcode;
        this.otherOpcode = otherOpcode;
    }

    DelayedInstruction(int opcode) {
        this.opcode = opcode;
        this.otherOpcode = null;
    }

    public void emitConstant(Compiler compiler) {
        LabelNode labelTrue = new LabelNode();
        LabelNode labelRet = new LabelNode();
        if (otherOpcode != null)
            compiler.emit(new InsnNode(this.otherOpcode));

        compiler.emit(new JumpInsnNode(this.opcode, labelTrue),
                new InsnNode(Opcodes.ICONST_0),
                new JumpInsnNode(Opcodes.GOTO, labelRet),
                labelTrue,
                new InsnNode(Opcodes.ICONST_1),
                labelRet);
    }

    public void emitJump(Compiler compiler, LabelNode labelNode) {
        if (otherOpcode != null)
            compiler.emit(new InsnNode(this.otherOpcode));
        compiler.emit(new JumpInsnNode(this.opcode, labelNode));
    }

    public void applyStackChanges(Compiler compiler) {
        compiler.notifyPopStack();
        compiler.notifyPopStack();
        compiler.notifyPushStack(StackTypes.BOOLEAN);
    }

    public abstract DelayedInstruction invert();
}
