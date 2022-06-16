/*
 * Copyright 2022 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.java.controlflow;

import lombok.AllArgsConstructor;
import org.openrewrite.Cursor;
import org.openrewrite.Tree;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Statement;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@AllArgsConstructor(staticName = "startingAt")
public final class ControlFlow {
    private final Cursor start;

    public ControlFlowSummary findControlFlow() {
        Cursor methodDeclarationBlockCursor = getMethodDeclarationBlockCursor();
        ControlFlowNode.Start start = ControlFlowNode.Start.create();
        ControlFlowAnalysis<Integer> analysis = new ControlFlowAnalysis<>(start, true);
        analysis.visit(methodDeclarationBlockCursor.getValue(), 1, methodDeclarationBlockCursor);
        ControlFlowNode.End end = (ControlFlowNode.End) analysis.current.iterator().next();
        return ControlFlowSummary.forGraph(start, end);
    }

    private Cursor getMethodDeclarationBlockCursor() {
        Iterator<Cursor> cursorPath = start.getPathAsCursors();
        Cursor methodDeclarationBlockCursor = null;
        while (cursorPath.hasNext()) {
            Cursor nextCursor = cursorPath.next();
            Object next = nextCursor.getValue();
            if (next instanceof J.Block) {
                methodDeclarationBlockCursor = nextCursor;
                if (((J.Block) next).isStatic()) {
                    break;
                }
            } else if (next instanceof J.MethodDeclaration) {
                break;
            }
        }
        if (methodDeclarationBlockCursor == null) {
            throw new IllegalArgumentException(
                    "Invalid start point: Could not find a Method Declaration to begin computing Control Flow"
            );
        }
        return methodDeclarationBlockCursor;
    }

    private static class ControlFlowAnalysis<P> extends JavaIsoVisitor<P> {

        /**
         * @implNote This MUST be 'protected' or package-private. This is set by annonymous inner classes.
         */
        protected Set<? extends ControlFlowNode> current;

        private final boolean methodEntryPoint;

        /**
         * Flows that terminate in a {@link J.Return} or {@link J.Throw} statement.
         */
        private final Set<ControlFlowNode> exitFlow = new HashSet<>();
        private boolean jumps;

        ControlFlowAnalysis(ControlFlowNode start, boolean methodEntryPoint) {
            this.current = Collections.singleton(Objects.requireNonNull(start, "start cannot be null"));
            this.methodEntryPoint = methodEntryPoint;
        }

        ControlFlowAnalysis(Set<? extends ControlFlowNode> current) {
            this.current = Objects.requireNonNull(current, "current cannot be null");
            this.methodEntryPoint = false;
        }

        ControlFlowNode.BasicBlock currentAsBasicBlock() {
            jumps = false;
            assert !current.isEmpty() : "No current node!";
            if (current.size() == 1 && current.iterator().next() instanceof ControlFlowNode.BasicBlock) {
                return (ControlFlowNode.BasicBlock) current.iterator().next();
            } else {
                if (!exitFlow.isEmpty()) {
                    return addBasicBlockToCurrent();
                }
                return addBasicBlockToCurrent();
            }
        }

        ControlFlowNode.BasicBlock addBasicBlockToCurrent() {
            Set<ControlFlowNode> newCurrent = new HashSet<>(current);
            ControlFlowNode.BasicBlock basicBlock = addBasicBlock(newCurrent);
            current = Collections.singleton(basicBlock);
            return basicBlock;
        }

        <C extends ControlFlowNode> C addSuccessorToCurrent(C node) {
            current.forEach(c -> c.addSuccessor(node));
            current = Collections.singleton(node);
            return node;
        }

        private void addCursorToBasicBlock() {
            currentAsBasicBlock().addNodeToBasicBlock(getCursor());
        }

        ControlFlowAnalysis<P> visitRecursive(Set<? extends ControlFlowNode> start, Tree toVisit, P param) {
            ControlFlowAnalysis<P> analysis = new ControlFlowAnalysis<>(start);
            analysis.visit(toVisit, param, getCursor());
            return analysis;
        }

        ControlFlowAnalysis<P> visitRecursiveTransferringDefault(Set<? extends ControlFlowNode> start, Tree toVisit, P param) {
            final ControlFlowAnalysis<P> analysis = visitRecursive(start, toVisit, param);
            if (!analysis.exitFlow.isEmpty()) {
                this.exitFlow.addAll(analysis.exitFlow);
            }
            return analysis;
        }

        @Override
        public J.Block visitBlock(J.Block block, P p) {
            addCursorToBasicBlock();
            for (Statement statement : block.getStatements()) {
                ControlFlowAnalysis<P> analysis = visitRecursive(current, statement, p);
                current = analysis.current;
                jumps = analysis.jumps;
                exitFlow.addAll(analysis.exitFlow);
            }
            if (methodEntryPoint) {
                ControlFlowNode end = ControlFlowNode.End.create();
                if (!jumps) {
                    addSuccessorToCurrent(end);
                }
                exitFlow.forEach(exit -> exit.addSuccessor(end));
                current = Collections.singleton(end);
            }
            return block;
        }

        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, P p) {
            visit(method.getSelect(), p); // First the select is invoked
            visit(method.getArguments(), p); // Then the arguments are invoked
            addCursorToBasicBlock(); // Then the method invocation
            return method;
        }

        @Override
        public J.NewClass visitNewClass(J.NewClass newClass, P p) {
            visit(newClass.getEnclosing(), p); // First the enclosing is invoked
            visit(newClass.getArguments(), p); // Then the arguments are invoked
            addCursorToBasicBlock(); // Then the new class
            // TODO: Maybe invoke a visitor on the body? (Anonymous inner classes)
            return newClass;
        }

        @Override
        public J.Literal visitLiteral(J.Literal literal, P p) {
            addCursorToBasicBlock();
            return literal;
        }

        @Override
        public <T extends J> J.Parentheses<T> visitParentheses(J.Parentheses<T> parens, P p) {
            addCursorToBasicBlock();
            visit(parens.getTree(), p);
            return parens;
        }

        @Override
        public <T extends J> J.ControlParentheses<T> visitControlParentheses(J.ControlParentheses<T> controlParens, P p) {
            addCursorToBasicBlock();
            visit(controlParens.getTree(), p);
            return controlParens;
        }

        @Override
        public J.TypeCast visitTypeCast(J.TypeCast typeCast, P p) {
            visit(typeCast.getExpression(), p);
            addCursorToBasicBlock();
            return typeCast;
        }

        @Override
        public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVariable, P p) {
            for (J.VariableDeclarations.NamedVariable variable : multiVariable.getVariables()) {
                visit(variable.getInitializer(), p); // First the initializer is invoked
            }
            addCursorToBasicBlock(); // Then the variable declaration
            return multiVariable;
        }

        @Override
        public J.VariableDeclarations.NamedVariable visitVariable(J.VariableDeclarations.NamedVariable variable, P p) {
            visit(variable.getInitializer(), p); // First add the initializer
            visit(variable.getName(), p); // Then add the name
            addCursorToBasicBlock(); // Then add the variable declaration
            return variable;
        }

        @Override
        public J.Unary visitUnary(J.Unary unary, P p) {
            if (unary.getOperator() == J.Unary.Type.Not) {
                addCursorToBasicBlock();
                visit(unary.getExpression(), p);
                current.forEach(controlFlowNode -> {
                    if (controlFlowNode instanceof ControlFlowNode.BasicBlock) {
                        ControlFlowNode.BasicBlock basicBlock = (ControlFlowNode.BasicBlock) controlFlowNode;
                        basicBlock.invertNextConditional();
                    }
                });
            } else {
                visit(unary.getExpression(), p); // The expression is invoked
                addCursorToBasicBlock(); // Then the unary
            }
            return unary;
        }

        private static Set<ControlFlowNode.ConditionNode> allAsConditionNodesMissingTruthFirst(Set<? extends ControlFlowNode> nodes) {
            return nodes.stream().map(controlFlowNode -> {
                if (controlFlowNode instanceof ControlFlowNode.ConditionNode) {
                    return ((ControlFlowNode.ConditionNode) controlFlowNode);
                } else {
                    return controlFlowNode.addConditionNodeTruthFirst();
                }
            }).collect(Collectors.toSet());
        }

        private static Set<ControlFlowNode.ConditionNode> allAsConditionNodesMissingFalseFirst(Set<? extends ControlFlowNode> nodes) {
            return nodes.stream().map(controlFlowNode -> {
                if (controlFlowNode instanceof ControlFlowNode.ConditionNode) {
                    return ((ControlFlowNode.ConditionNode) controlFlowNode);
                } else {
                    return controlFlowNode.addConditionNodeFalseFirst();
                }
            }).collect(Collectors.toSet());
        }

        private static ControlFlowNode.ConditionNode getControlFlowNodeMissingSuccessors(Set<ControlFlowNode.ConditionNode> nodes) {
            for (ControlFlowNode.ConditionNode node : nodes) {
                if (node.getTruthySuccessor() == null || node.getFalsySuccessor() == null) {
                    return node;
                }
            }
            throw new IllegalArgumentException("No control flow node missing successors");
        }

        private interface BranchingAdapter {
            Expression getCondition();

            J getTruePart();

            @Nullable
            J getFalsePart();

            static BranchingAdapter of(J.If ifStatement) {
                return new BranchingAdapter() {
                    @Override
                    public Expression getCondition() {
                        return ifStatement.getIfCondition().getTree();
                    }

                    @Override
                    public J getTruePart() {
                        return ifStatement.getThenPart();
                    }

                    @Override
                    public @Nullable J getFalsePart() {
                        return ifStatement.getElsePart();
                    }
                };
            }

            static BranchingAdapter of(J.Ternary ternary) {
                return new BranchingAdapter() {
                    @Override
                    public Expression getCondition() {
                        return ternary.getCondition();
                    }

                    @Override
                    public J getTruePart() {
                        return ternary.getTruePart();
                    }

                    @Override
                    public @Nullable J getFalsePart() {
                        return ternary.getFalsePart();
                    }
                };
            }
        }

        private void visitBranching(BranchingAdapter branching, P p) {
            addCursorToBasicBlock(); // Add the if node first

            // First the condition is invoked
            ControlFlowAnalysis<P> conditionAnalysis =
                    visitRecursiveTransferringDefault(current, branching.getCondition(), p);

            Set<ControlFlowNode.ConditionNode> conditionNodes = allAsConditionNodesMissingTruthFirst(conditionAnalysis.current);
            // Then the then block is visited
            ControlFlowAnalysis<P> thenAnalysis =
                    visitRecursiveTransferringDefault(conditionNodes, branching.getTruePart(), p);
            Set<ControlFlowNode> newCurrent = Collections.singleton(getControlFlowNodeMissingSuccessors(conditionNodes));
            boolean exhaustiveJump = thenAnalysis.jumps;
            if (branching.getFalsePart() != null) {
                // Then the else block is visited
                ControlFlowAnalysis<P> elseAnalysis =
                        visitRecursiveTransferringDefault(newCurrent, branching.getFalsePart(), p);
                current = Stream.concat(
                        thenAnalysis.current.stream(),
                        elseAnalysis.current.stream()
                ).collect(Collectors.toSet());
                exhaustiveJump &= elseAnalysis.jumps;
            } else {
                current = newCurrent;
            }
            jumps = exhaustiveJump;
        }

        @Override
        public J.Ternary visitTernary(J.Ternary ternary, P p) {
            visitBranching(BranchingAdapter.of(ternary), p);
            return ternary;
        }

        @Override
        public J.If visitIf(J.If iff, P p) {
            visitBranching(BranchingAdapter.of(iff), p);
            return iff;
        }

        @Override
        public J.Binary visitBinary(J.Binary binary, P p) {
            if (J.Binary.Type.And.equals(binary.getOperator())) {
                ControlFlowAnalysis<P> left = visitRecursive(current, binary.getLeft(), p);
                Set<ControlFlowNode.ConditionNode> conditionNodes = allAsConditionNodesMissingTruthFirst(left.current);
                ControlFlowAnalysis<P> right = visitRecursive(
                        conditionNodes,
                        binary.getRight(),
                        p
                );
                current = Stream.concat(
                        right.current.stream(),
                        Stream.of(getControlFlowNodeMissingSuccessors(conditionNodes))
                ).collect(Collectors.toSet());
            } else if (J.Binary.Type.Or.equals(binary.getOperator())) {
                ControlFlowAnalysis<P> left = visitRecursive(current, binary.getLeft(), p);
                Set<ControlFlowNode.ConditionNode> conditionNodes = allAsConditionNodesMissingFalseFirst(left.current);
                ControlFlowAnalysis<P> right = visitRecursive(
                        conditionNodes,
                        binary.getRight(),
                        p
                );
                current = Stream.concat(
                        Stream.of(getControlFlowNodeMissingSuccessors(conditionNodes)),
                        right.current.stream()
                ).collect(Collectors.toSet());
            } else {
                visit(binary.getLeft(), p); // First the left is invoked
                visit(binary.getRight(), p); // Then the right is invoked
                addCursorToBasicBlock(); // Add the binary node last
            }
            return binary;
        }

        @Override
        public J.DoWhileLoop visitDoWhileLoop(J.DoWhileLoop doWhileLoop, P p) {
            addCursorToBasicBlock(); // Add the while node first
            ControlFlowNode.BasicBlock entryBlock = currentAsBasicBlock();
            ControlFlowNode.BasicBlock basicBlock = entryBlock.addBasicBlock();
            ControlFlowAnalysis<P> bodyAnalysis =
                    visitRecursiveTransferringDefault(Collections.singleton(basicBlock), doWhileLoop.getBody(), p); // First  the body is visited
            ControlFlowAnalysis<P> conditionAnalysis =
                    visitRecursive(bodyAnalysis.current, doWhileLoop.getWhileCondition().getTree(), p); // Then the condition is invoked
            Set<ControlFlowNode.ConditionNode> conditionNodes =
                    allAsConditionNodesMissingTruthFirst(conditionAnalysis.current);
            // Add the 'loop' in
            conditionNodes.forEach(
                    controlFlowNode -> controlFlowNode.addSuccessor(basicBlock)
            );
            current = Collections.singleton(
                    getControlFlowNodeMissingSuccessors(conditionNodes)
            );
            return doWhileLoop;
        }

        @Override
        public J.WhileLoop visitWhileLoop(J.WhileLoop whileLoop, P p) {
            addCursorToBasicBlock(); // Add the while node first
            ControlFlowNode.BasicBlock entryBlock = currentAsBasicBlock();
            ControlFlowAnalysis<P> conditionAnalysis =
                    visitRecursive(Collections.singleton(entryBlock), whileLoop.getCondition().getTree(), p); // First the condition is invoked
            Set<ControlFlowNode.ConditionNode> conditionNodes =
                    allAsConditionNodesMissingTruthFirst(conditionAnalysis.current);
            ControlFlowAnalysis<P> bodyAnalysis =
                    visitRecursiveTransferringDefault(conditionNodes, whileLoop.getBody(), p); // Then the body is visited
            // Add the 'loop' in
            bodyAnalysis.current.forEach(
                    controlFlowNode -> controlFlowNode.addSuccessor(entryBlock.getSuccessor())
            );
            current = Collections.singleton(
                    getControlFlowNodeMissingSuccessors(conditionNodes)
            );
            return whileLoop;
        }

        @Override
        public J.ForLoop visitForLoop(J.ForLoop forLoop, P p) {
            // Basic Block has
            //  - For Loop Statement
            //  - Initialization
            //  - Condition
            //  Node has
            //  - Condition
            // Basic Block has
            //  - Body
            //  - Update
            addCursorToBasicBlock(); // Add the for node first
            // First the control is invoked
            final ControlFlowNode.BasicBlock[] entryBlock = new ControlFlowNode.BasicBlock[1];
            ControlFlowAnalysis<P> controlAnalysisFirstBit = new ControlFlowAnalysis<P>(current) {
                @Override
                public J.ForLoop.Control visitForControl(J.ForLoop.Control control, P p) {
                    // First the initialization is invoked
                    visit(control.getInit(), p);
                    entryBlock[0] = currentAsBasicBlock();
                    // Then the condition is invoked
                    ControlFlowAnalysis<P> conditionAnalysis =
                            visitRecursive(current, control.getCondition(), p);
                    current = allAsConditionNodesMissingTruthFirst(conditionAnalysis.current);
                    return control;
                }
            };
            controlAnalysisFirstBit.visit(forLoop.getControl(), p, getCursor());

            // Then the body is invoked
            ControlFlowAnalysis<P> bodyAnalysis =
                    visitRecursiveTransferringDefault(controlAnalysisFirstBit.current, forLoop.getBody(), p);
            // Then the update is invoked
            ControlFlowAnalysis<P> controlAnalysisSecondBit = new ControlFlowAnalysis<P>(bodyAnalysis.current) {
                @Override
                public J.ForLoop.Control visitForControl(J.ForLoop.Control control, P p) {
                    // Now the update is invoked
                    visit(control.getUpdate(), p);
                    return control;
                }
            };
            controlAnalysisSecondBit.visit(forLoop.getControl(), p, getCursor());
            controlAnalysisSecondBit.current.forEach(
                    controlFlowNode -> controlFlowNode.addSuccessor(entryBlock[0].getSuccessor())
            );
            current = Collections.singleton(
                    getControlFlowNodeMissingSuccessors(
                            allAsConditionNodesMissingTruthFirst(controlAnalysisFirstBit.current)
                    )
            );
            return forLoop;
        }

        @Override
        public J.Identifier visitIdentifier(J.Identifier identifier, P p) {
            addCursorToBasicBlock();
            return identifier;
        }

        @Override
        public J.Return visitReturn(J.Return _return, P p) {
            visit(_return.getExpression(), p); // First the expression is invoked
            addCursorToBasicBlock(); // Then the return
            exitFlow.addAll(current);
            current = Collections.emptySet();
            jumps = true;
            return _return;
        }

        @Override
        public J.Throw visitThrow(J.Throw thrown, P p) {
            visit(thrown.getException(), p); // First the expression is invoked
            addCursorToBasicBlock(); // Then the return
            exitFlow.addAll(current);
            current = Collections.emptySet();
            jumps = true;
            return thrown;
        }

        @Override
        public J.ArrayAccess visitArrayAccess(J.ArrayAccess arrayAccess, P p) {
            addCursorToBasicBlock();
            return arrayAccess;
        }

        @Override
        public J.Try visitTry(J.Try _try, P p) {
            addCursorToBasicBlock();
            return _try;
        }

        @Override
        public J.Switch visitSwitch(J.Switch _switch, P p) {
            addCursorToBasicBlock();
            return _switch;
        }

//        @Override
//        public J.Case visitCase(J.Case _case, P p) {
//            addCursorToBasicBlock();
//            return _case;
//        }

        private static ControlFlowNode.BasicBlock addBasicBlock(Collection<ControlFlowNode> nodes) {
            if (nodes.isEmpty()) {
                throw new IllegalStateException("No nodes to add to a basic block!");
            }
            Iterator<ControlFlowNode> cfnIterator = nodes.iterator();
            ControlFlowNode.BasicBlock basicBlock = cfnIterator.next().addBasicBlock();
            while (cfnIterator.hasNext()) {
                cfnIterator.next().addSuccessor(basicBlock);
            }
            return basicBlock;
        }
    }
}
