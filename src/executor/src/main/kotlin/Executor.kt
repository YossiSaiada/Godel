import arrow.core.*
import arrow.core.extensions.option.applicative.just
import java.util.Stack

typealias Context = MutableMap<String, Executor.Object>

class GodelRuntimeError(message: String) : Error(message)

class Executor(
    val classes: Map<String, ASTNode.ClassDeclaration>,
    val classDescriptions: Map<ASTNode.Type, ClassDescription>
) {
    val contextStack: Stack<Context> = Stack()
    val returnStack: Stack<Object> = Stack()

    sealed class Object(
        open val type: ASTNode.Type
    ) {
        sealed class Primitive<T>(
            type: ASTNode.Type,
            val innerValue: T
        ) : Object(type) {
            class CoreBoolean(innerValue: Boolean) : Primitive<Boolean>(ASTNode.Type.Core.boolean, innerValue)
            class CoreInt(innerValue: Int) : Primitive<Int>(ASTNode.Type.Core.int, innerValue)
            class CoreFloat(innerValue: Float) : Primitive<Float>(ASTNode.Type.Core.float, innerValue)
            class CoreString(innerValue: String) : Primitive<String>(ASTNode.Type.Core.string, innerValue)
            class CoreUnit() : Primitive<Unit>(ASTNode.Type.Core.unit, Unit)
        }

        class Complex(
            type: ASTNode.Type,
            val state: Map<String, Object>
        ) : Object(type)

        class Function(
            type: ASTNode.Type,
            val functionDeclaration: ASTNode.FunctionDeclaration,
            val context: Context
        ) : Object(type)
    }

    sealed class BreakType {
        class Return(val value: Object) : BreakType()
    }

    fun run(mainFunction: ASTNode.FunctionDeclaration) {
        // TODO: evaluate Invocation of main and print the returned String.
        evaluate(mainFunction.body)
    }

    private fun evaluate(statements: ASTNode.Statements): Either<BreakType, Object> {
        for (statement in statements) {
            val result = evaluate(statement)
            if (result.isLeft())
                return result
        }
        return Either.right(Object.Primitive.CoreUnit())
    }

    private fun evaluate(statement: ASTNode.Statement): Either<BreakType, Object> {
        return when (statement) {
            is ASTNode.Expression -> evaluate(statement)
            is ASTNode.If.Statement -> evaluate(statement)
            is ASTNode.ValDeclaration -> evaluate(statement)
            else -> error(statement::class.simpleName!!)
        }
    }

    /**
     * A().a()
     * (if (x) { A().a } else { B().b })("a")
     * Invocation(
     *  function = BinaryExpression(
     *      left = A(),
     *      operator = ".",
     *      right = "a"
     *  ),
     *  arguments = emptyList()
     * )
     */
    private fun evaluate(expression: ASTNode.Expression): Either<BreakType, Object> {
        return when (expression) {
            is ASTNode.BinaryExpression<*, *> -> evaluate(expression)
            is ASTNode.BooleanLiteral -> evaluate(expression)
            is ASTNode.If.Expression -> evaluate(expression)
            is ASTNode.FloatLiteral -> evaluate(expression)
            is ASTNode.InfixExpression<*, *> -> evaluate(expression)
            is ASTNode.IntLiteral -> evaluate(expression)
            is ASTNode.Invocation -> evaluate(expression)
            is ASTNode.Lambda -> evaluate(expression)
            is ASTNode.If.NegativeBranchOnly ->
                error("negativeBranchOnly")
            is ASTNode.Return -> evaluate(expression)
            is ASTNode.StringLiteral -> evaluate(expression)
            is ASTNode.Unit -> evaluate(expression)
            else ->
                error(expression::class.simpleName!!)
        }
    }


    private fun evaluate(invocation: ASTNode.Invocation): Either<BreakType, Object> {
        return when (val functionEvaluated = evaluate(invocation.function)) {
            is Either.Left ->
                functionEvaluated
            is Either.Right -> {
                val functionObject = functionEvaluated.b
                if (functionObject is Object.Function) {
                    contextStack.push(invocation.arguments.mapIndexed { index, argument ->
                        functionObject.functionDeclaration.parameters[index].name to (evaluate(argument.value) as Either.Right).b
                    }.toMap().toMutableMap())
                    functionObject.functionDeclaration.parameters
                    evaluate(functionObject.functionDeclaration.body).fold(
                        ifLeft = { (it as BreakType.Return).value.right() },
                        ifRight = { it.right() }
                    )
                } else {
                    error("")
                }
            }
        }
    }

    private fun evaluate(lambda: ASTNode.Lambda): Either<BreakType, Object> =
        Object.Function(
            functionDeclaration = ASTNode.FunctionDeclaration(
                name = "",
                typeParameters = emptyList(),
                returnType = lambda.returnValue.actualType,
                parameters = lambda.parameters.map { ASTNode.Parameter(it.first, it.second) },
                body = lambda.statements
            ),
            context = mergeContext(contextStack),
            type = ASTNode.Type.Functional(lambda.parameters.map { it.second }, lambda.returnValue.actualType, false)
        ).right()

    private fun mergeContext(contexts: Stack<Context>): Context {
        val contextsList = contexts.toMutableList().reversed()
        val resultContext = mutableMapOf<String, Executor.Object>()
        for (context in contextsList) {
            resultContext.putAll(resultContext + context)
        }
        return resultContext
    }

    private fun evaluate(returnExpression: ASTNode.Return): Either<BreakType, Object> {
        return evaluate(returnExpression.value).flatMap {
            Either.left(BreakType.Return(it))
        }
    }

    private fun evaluate(floatLiteral: ASTNode.FloatLiteral): Either<BreakType, Object> =
        Object.Primitive.CoreFloat(floatLiteral.value).right()

    private fun evaluate(intLiteral: ASTNode.IntLiteral): Either<BreakType, Object> =
        Object.Primitive.CoreInt(intLiteral.value).right()

    private fun evaluate(stringLiteral: ASTNode.StringLiteral): Either<BreakType, Object> =
        Object.Primitive.CoreString(stringLiteral.value).right()

    private fun evaluate(unit: ASTNode.Unit): Either<BreakType, Object> =
        Object.Primitive.CoreUnit().right()

    private fun evaluate(booleanLiteral: ASTNode.BooleanLiteral): Either<BreakType, Object> =
        Object.Primitive.CoreBoolean(booleanLiteral.value).right()

    private fun evaluate(ifExpression: ASTNode.If.Expression): Either<BreakType, Object> {
        val conditionEvaluated =
            (evaluate(ifExpression.condition) as? Object.Primitive.CoreBoolean ?: error("זה לא בולאן")).innerValue
        contextStack.push(mutableMapOf())
        //TODO: test what happens when one branch have multiple statements in it
        return (
                if (conditionEvaluated) evaluate(ifExpression.positiveBranch)
                else evaluate(ifExpression.negativeBranch)
                ).also { contextStack.pop() }
    }

    // TODO: safeAccess
    private fun getMember(godelObject: Object, memberName: String, safeAccess: Boolean): Object {
        val godelObjectType =
            (godelObject.type as? ASTNode.Type.Regular)
                ?: throw GodelRuntimeError("Member access for functional types isn't available currently.")
        val classDescription =
            classDescriptions[godelObject.type]
                ?: throw GodelRuntimeError("Unable to find class description for type ${godelObject.type}.")
        val member = classDescription.allMembers.find { it.name == memberName }
            ?: throw GodelRuntimeError("Unable to find member named $memberName in class ${godelObject.type}.")
        return when (member) {
            is ClassDescription.Member.Property ->
                when (godelObject) {
                    is Object.Primitive<*> ->
                        throw GodelRuntimeError("Primitive objects doesn't have properties.")
                    is Object.Function ->
                        throw GodelRuntimeError("Functional objects can't have properties currently.")
                    is Object.Complex ->
                        return godelObject.state[memberName]
                            ?: throw GodelRuntimeError("Unknown error. Let's try to figure out when we arrive here, and then edit this message.")
                }
            is ClassDescription.Member.Method ->
                when (godelObject) {
                    is Object.Primitive<*> -> {
                        val nativeFunction = coreClassImplementations[godelObjectType]?.get(memberName)
                            ?: throw GodelRuntimeError("Cannot invoke method $memberName on type $godelObjectType.")
                        nativeFunction.value(
                            godelObject,
                            emptyList()
                        ) //TODO: we don't want to execute it immediately, just pass a reference to it.
                    }
                    is Object.Function ->
                        throw GodelRuntimeError("Functional objects can't have properties currently.")
                    is Object.Complex -> {
                        val classDeclaration = classes[godelObject.type.toString()]
                            ?: throw GodelRuntimeError("Unable to find class declaration for type ${godelObject.type}.")
                        val methodDefinition =
                            classDeclaration.members.mapNotNull { it.declaration as? ASTNode.FunctionDeclaration }
                                .find { it.name == memberName }
                                ?: throw GodelRuntimeError("Unable to find method named $memberName for type ${godelObject.type}.")
                        Object.Function(
                            type = ASTNode.Type.Functional(
                                methodDefinition.parameters.map { it.type },
                                methodDefinition.returnType,
                                nullable = false
                            ),
                            functionDeclaration = methodDefinition,
                            context = mergeContext(contextStack)
                        )
                    }
                }
        }
    }

    private fun evaluate(binaryExpression: ASTNode.BinaryExpression<*, *>): Object {
        return when (binaryExpression.operator) {
            ASTNode.BinaryOperator.Dot -> {
                val leftObject = evaluate(binaryExpression.left)
                val memberName = (binaryExpression.right as ASTNode.Identifier).value
                getMember(leftObject, memberName, safeAccess = false)
            }
            ASTNode.BinaryOperator.NullAwareDot -> {
                val leftObject = evaluate(binaryExpression.left)
                val memberName = (binaryExpression.right as ASTNode.Identifier).value
                getMember(leftObject, memberName, safeAccess = true)
            }
            ASTNode.BinaryOperator.Elvis -> {
                val leftObject = evaluate(binaryExpression.left)
                leftObject //TODO: check if [leftObject] is null.
            }
            else -> {
                evaluate(
                    ASTNode.Invocation(
                        function = ASTNode.BinaryExpression(
                            binaryExpression.left,
                            ASTNode.BinaryOperator.Dot,
                            ASTNode.Identifier(binaryExpression.operator.asString)
                        ),
                        actualType = ASTNode.Type.Unknown,
                        typeArguments = emptyList(),
                        arguments = listOf(
                            ASTNode.FunctionArgument(name = null, value = binaryExpression.right)
                        )
                    )
                )
            }
        }
    }

    private fun evaluate(onlyIf: ASTNode.If.Statement): Either<BreakType, Object.Primitive.CoreUnit> {
        val conditionEvaluated =
            (evaluate(onlyIf.condition) as? Object.Primitive<*> ?: error("זה לא מרימיטב")).innerValue as? Boolean
                ?: error("זה לא בולאן")
        contextStack.push(mutableMapOf())
        if (conditionEvaluated)
            evaluate(onlyIf.positiveBranch)
        else
            onlyIf.negativeBranch?.let { evaluate(it) }
        contextStack.pop()
        return Object.Primitive.CoreUnit().right()
    }

    private fun evaluate(valDeclaration: ASTNode.ValDeclaration) =
        evaluate(valDeclaration.value).map {
            contextStack.peek()[valDeclaration.name] = it
            Object.Primitive.CoreUnit()
        }

}
