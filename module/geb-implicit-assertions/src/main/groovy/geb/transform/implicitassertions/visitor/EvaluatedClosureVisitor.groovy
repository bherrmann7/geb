package geb.transform.implicitassertions.visitor

import geb.transform.implicitassertions.Runtime
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.FieldNode

import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.codehaus.groovy.syntax.SyntaxException
import org.codehaus.groovy.ast.expr.*
import static org.codehaus.groovy.syntax.Types.ASSIGNMENT_OPERATOR
import static org.codehaus.groovy.syntax.Types.ofType
import org.codehaus.groovy.ast.stmt.IfStatement

import org.codehaus.groovy.ast.ClassNode

import org.codehaus.groovy.ast.stmt.AssertStatement

class EvaluatedClosureVisitor extends ClassCodeVisitorSupport {
	SourceUnit sourceUnit

	EvaluatedClosureVisitor(SourceUnit sourceUnit) {
		this.sourceUnit = sourceUnit
	}

	@Override
	void visitField(FieldNode node) {
		if (node.static && node.initialExpression in ClosureExpression) {
			switch (node.name) {
				case 'at':
					rewriteClosureStatements(node.initialExpression)
					break
				case 'content':
					visitContentDslStatements(node.initialExpression)
					break
			}
		}
	}

	private boolean lastArgumentIsClosureExpression(ArgumentListExpression arguments) {
		arguments.expressions && arguments.expressions[-1] in ClosureExpression
	}

	@Override
	void visitExpressionStatement(ExpressionStatement statement) {
		if (statement.expression in MethodCallExpression) {
			MethodCallExpression expression = statement.expression
			if (expression.methodAsString == 'waitFor' && expression.arguments in ArgumentListExpression) {
				ArgumentListExpression arguments = expression.arguments
				if (lastArgumentIsClosureExpression(arguments)) {
					rewriteClosureStatements(arguments.expressions[-1])
				}
			} else {
				compensateForSpockIfNecessary(expression)	
			}
		}
	}

	void compensateForSpockIfNecessary(MethodCallExpression expression) {
		if (expression.objectExpression in ClassExpression && expression.method in ConstantExpression) {
			ClassExpression classExpression = expression.objectExpression as ClassExpression
			ConstantExpression method = expression.method as ConstantExpression
			
			if (classExpression.type.name == "org.spockframework.runtime.SpockRuntime" && method.value == "verifyMethodCondition") {
				if (expression.arguments in ArgumentListExpression) {
					ArgumentListExpression arguments = expression.arguments as ArgumentListExpression
					List<Expression> argumentExpressions = arguments.expressions
					
					if (argumentExpressions.size() >= 8) {
						Expression verifyMethodConditionMethodArg = argumentExpressions.get(6)
						String methodName = getConstantValueOfType(extractRecordedValueExpression(verifyMethodConditionMethodArg), String)
						
						if (methodName) {
							Expression verifyMethodConditionArgsArgument = argumentExpressions.get(7)
							if (verifyMethodConditionArgsArgument in ArrayExpression) {
								 
		                        List<Expression> values = (verifyMethodConditionArgsArgument as ArrayExpression).expressions.collect { Expression argumentExpression ->
									extractRecordedValueExpression(argumentExpression)									
								} 
								
								visitSpockValueRecordMethodCall(methodName, values)
							}
						}	
					}
				}
			}
		}
	}
	
	
	Expression extractRecordedValueExpression(Expression valueRecordExpression) {
		if (valueRecordExpression in MethodCallExpression) {
			MethodCallExpression methodCallExpression = valueRecordExpression as MethodCallExpression

			if (methodCallExpression.arguments in ArgumentListExpression) {
				ArgumentListExpression arguments = methodCallExpression.arguments as ArgumentListExpression

				if (arguments.expressions.size() >= 2) {
					return arguments.expressions.get(1)
				}
			}
		}
		
		null
	}
	
	def getConstantValueOfType(Expression expression, Class type) {
		if (expression != null && expression in ConstantExpression) {
			Object value = ((ConstantExpression)expression).value
			type.isInstance(value) ? value : null
		} else {
			null
		}
	}

	void visitSpockValueRecordMethodCall(String name, List<Expression> arguments) {
		if (name == "waitFor") {
			if (!arguments.empty) { 
				Expression lastArg = arguments.last()
				if (lastArg instanceof ClosureExpression) {
					rewriteClosureStatements(lastArg as ClosureExpression)
				}
			}
		}	
	}
	
	@Override
	protected SourceUnit getSourceUnit() {
		sourceUnit
	}

	private boolean waitOptionIsSpecified(ArgumentListExpression arguments) {
		MapExpression paramMap = arguments.expressions.find { it in MapExpression }
		paramMap?.mapEntryExpressions.any {
			if (it.keyExpression in ConstantExpression) {
				ConstantExpression key = it.keyExpression
				key.value == 'wait'
			}
		}
	}

	private void visitContentDslStatements(ClosureExpression closureExpression) {
		BlockStatement blockStatement = closureExpression.code
		blockStatement.statements.each { Statement statement ->
			if (statement in ExpressionStatement) {
				ExpressionStatement expressionStatement = statement
				if (expressionStatement.expression in MethodCallExpression) {
					MethodCallExpression methodCall = expressionStatement.expression
					if (methodCall.arguments in ArgumentListExpression) {
						ArgumentListExpression arguments = methodCall.arguments
						if (lastArgumentIsClosureExpression(arguments) && waitOptionIsSpecified(arguments)) {
							rewriteClosureStatements(arguments.expressions[-1])
						}
					}
				}
			}
		}
	}

	private void rewriteClosureStatements(ClosureExpression closureExpression) {
		BlockStatement blockStatement = closureExpression.code
		ListIterator iterator = blockStatement.statements.listIterator()
		while (iterator.hasNext()) {
			iterator.set(rewriteClosureStatement(iterator.next()))
		}
	}

	private Statement rewriteClosureStatement(Statement statement) {
		Statement result = statement
		Expression toBeRewritten = getExpressionToBeRewritten(statement)
		if (toBeRewritten) {
			result = encloseWithVoidCheckAndAssert(toBeRewritten, statement)
		}
		return result
	}

	private Expression getExpressionToBeRewritten(Statement statement) {
		if (statement in ExpressionStatement) {
			ExpressionStatement expressionStatement = statement
			if (!(expressionStatement.expression in DeclarationExpression)
				&& checkIsValidCondition(expressionStatement)) {
				return expressionStatement.expression
			}
		}
	}

	boolean checkIsValidCondition(ExpressionStatement statement) {
		if (statement.expression in BinaryExpression) {
			BinaryExpression binaryExpression = statement.expression
			if (ofType(binaryExpression.operation.type, ASSIGNMENT_OPERATOR)) {
				reportError(statement, "Expected a condition, but found an assignment. Did you intend to write '==' ?")
				false
			}
		}
		true
	}

	private Statement encloseWithVoidCheckAndAssert(Expression toBeRewritten, Statement original) {
		Statement replacement

		Expression recordedValueExpression = createRuntimeCall("recordValue", toBeRewritten)
		BooleanExpression booleanExpression = new BooleanExpression(recordedValueExpression)

		Statement retrieveRecordedValueStatement = new ExpressionStatement(createRuntimeCall("retrieveRecordedValue"))

		Statement withAssertion = new AssertStatement(booleanExpression)
		withAssertion.setSourcePosition(toBeRewritten)
		withAssertion.setStatementLabel((String) toBeRewritten.getNodeMetaData("statementLabel"));

		BlockStatement assertAndRetrieveRecordedValue = new BlockStatement()
		assertAndRetrieveRecordedValue.addStatement(withAssertion)
		assertAndRetrieveRecordedValue.addStatement(retrieveRecordedValueStatement)

		if (toBeRewritten in MethodCallExpression) {
			MethodCallExpression rewrittenMethodCall = toBeRewritten

			Statement noAssertion = new ExpressionStatement(toBeRewritten)
			StaticMethodCallExpression isVoidMethod = createRuntimeCall(
				"isVoidMethod",
				rewrittenMethodCall.objectExpression,
				rewrittenMethodCall.method,
				toArgumentArray(rewrittenMethodCall.arguments)
			)

			replacement = new IfStatement(new BooleanExpression(isVoidMethod), noAssertion, assertAndRetrieveRecordedValue)
		} else {
			replacement = assertAndRetrieveRecordedValue
		}

		replacement.setSourcePosition(original)
		replacement
	}

	private StaticMethodCallExpression createRuntimeCall(String methodName, Expression... argumentExpressions) {
		ArgumentListExpression argumentListExpression = new ArgumentListExpression()
		for (Expression expression in argumentExpressions) {
			argumentListExpression.addExpression(expression)
		}

		new StaticMethodCallExpression(new ClassNode(Runtime), methodName, argumentListExpression)
	}

	private Expression toArgumentArray(Expression arguments) {
		List<Expression> argumentList
		if (arguments instanceof NamedArgumentListExpression) {
			argumentList = [arguments]
		} else {
			TupleExpression tuple = arguments
			argumentList = tuple.expressions
		}
		List<SpreadExpression> spreadExpressions = argumentList.findAll { it in SpreadExpression }
		if (spreadExpressions) {
			spreadExpressions.each { reportError(it, 'Spread expressions are not allowed here') }
			return null
		} else {
			new ArrayExpression(ClassHelper.OBJECT_TYPE, argumentList);
		}
	}

	private void reportError(ASTNode node, String message) {
		def line = node.lineNumber > 0 ? node.lineNumber : node.lastLineNumber
		def column = node.columnNumber > 0 ? node.columnNumber : node.lastColumnNumber
		def errorMessage = new SyntaxErrorMessage(new SyntaxException(message, line, column), sourceUnit)
		sourceUnit.errorCollector.addErrorAndContinue(errorMessage)
	}
}

