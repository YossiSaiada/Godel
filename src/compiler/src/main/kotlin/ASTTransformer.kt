object ASTTransformer {
    fun transformAST(rootNode: ParseTreeNode) =
        when (rootNode) {
            is ParseTreeNode.Inner -> transformProgram(rootNode)
            else -> throw CompilationError("Cannot transform AST from a tree whose root isn't a statements node.")
        }

    private fun transformProgram(rootNode: ParseTreeNode.Inner) =
        transformStatements(rootNode[1])
            .also { assertSemanticChecks(it) }

    private fun mergeIfBranches(statements: List<ASTNode.Statement>): List<ASTNode.Statement> =
        statements
            .fold(emptyList()) { statementsList, currentStatement ->
                val lastStatement = statementsList.lastOrNull()
                if (currentStatement is ASTNode.If.NegativeBranchOnly && lastStatement is ASTNode.If)
                    statementsList.dropLast(1) + lastStatement.mergedWith(currentStatement)
                else statementsList + currentStatement
            }

    private fun transformStatements(rootNode: ParseTreeNode): ASTNode.Statements {
        val rawStatements =
            when (rootNode) {
                is ParseTreeNode.EpsilonLeaf -> emptyList()
                is ParseTreeNode.Inner ->
                    rootNode.children
                        .filter { (it as? ParseTreeNode.Inner)?.type == Parser.InnerNodeType.Statement }
                        .map { transformStatement(it as ParseTreeNode.Inner) }
                else -> throwInvalidParseError()
            }
        return ASTNode.Statements(mergeIfBranches(rawStatements))
    }

    private fun transformStatement(rootNode: ParseTreeNode.Inner): ASTNode.Statement {
        val firstChild = rootNode[0] as ParseTreeNode.Inner
        return when (firstChild.type) {
            Parser.InnerNodeType.Expression -> transformExpressionOrStatement(firstChild)
            Parser.InnerNodeType.Declaration -> transformDeclaration(firstChild)
            else -> throwInvalidParseError("firstChild.type is ${rootNode.type}")
        }
    }

    private fun transformDeclaration(rootNode: ParseTreeNode.Inner): ASTNode.Statement {
        val firstChild = rootNode[0] as ParseTreeNode.Inner
        return when (firstChild.type) {
            Parser.InnerNodeType.ValDeclaration -> transformValDeclaration(firstChild)
            Parser.InnerNodeType.ClassDeclaration -> transformClassDeclaration(firstChild)
            Parser.InnerNodeType.FunctionDeclaration -> transformFunctionDeclaration(firstChild)
            else -> throwInvalidParseError("firstChild.type is ${rootNode.type}")
        }
    }

    private fun transformTypeStar(node: ParseTreeNode): List<ASTNode.Type> =
        when (node) {
            is ParseTreeNode.EpsilonLeaf -> emptyList()
            is ParseTreeNode.Inner ->
                when (node.type) {
                    Parser.InnerNodeType.TypeStar ->
                        listOf(transformType(node[0] as ParseTreeNode.Inner)) + transformTypeStar(node.last())
                    Parser.InnerNodeType.TypeStarRest -> transformTypeStar(node.last())
                    else -> throwInvalidParseError()
                }
            else -> throwInvalidParseError()
        }

    private fun transformType(node: ParseTreeNode.Inner): ASTNode.Type =
        when (node.children.size) {
            3 ->
                ASTNode.Type.Regular(
                    name = (node[0] as ParseTreeNode.Leaf).token.content,
                    typesParameters = transformTypeArguments(node[1]),
                    nullable = node.children[2] is ParseTreeNode.Inner
                )
            7 -> {
                val innerTypes = transformTypeStar(node[2])
                when (val functionalOrNullableTypeNode = node.last()) {
                    is ParseTreeNode.EpsilonLeaf -> innerTypes.single()
                    is ParseTreeNode.Inner ->
                        if (functionalOrNullableTypeNode.children.size == 1)
                            innerTypes.single().withNullable(true)
                        else
                            ASTNode.Type.Functional(
                                parameterTypes = innerTypes,
                                resultType = transformType(node.last().last() as ParseTreeNode.Inner),
                                nullable = false
                            )
                    else ->
                        throwInvalidParseError()
                }
            }
            else ->
                throwInvalidParseError()
        }

    private fun transformFunctionDeclaration(rootNode: ParseTreeNode.Inner): ASTNode.FunctionDeclaration {
        fun transformAnythingButBacktickPlus(node: ParseTreeNode.Inner): String =
            (node[0] as ParseTreeNode.Leaf).token.content +
                    node.children.getOrNull(1)
                        ?.let { transformAnythingButBacktickPlus(it as ParseTreeNode.Inner) }
                        .orEmpty()

        val functionName = rootNode[2] as ParseTreeNode.Inner
        val name = when (functionName.children.size) {
            1 -> (functionName[0] as ParseTreeNode.Leaf).token.content
            3 -> transformAnythingButBacktickPlus(functionName[1] as ParseTreeNode.Inner)
            else -> throwInvalidParseError()
        }
        return ASTNode.FunctionDeclaration(
            name = name,
            typeParameters = transformTypeParameters(rootNode[4]),
            parameters = transformFunctionParameters(rootNode[6]),
            returnType = (rootNode[8] as? ParseTreeNode.Inner)?.let { transformType(it[2] as ParseTreeNode.Inner) }
                ?: ASTNode.Type.Core.unit,
            body = transformStatements(rootNode[10][1][1])
        )
    }

    /* private fun transformType(node: ParseTreeNode.Inner) =
        ASTNode.Type(
            name = (node[0] as ParseTreeNode.Leaf).token.content,
            typesParameters = transformTypeParameters(node[1]),
            nullable = node.children[2] is ParseTreeNode.Inner
        )*/

    private fun transformParameter(node: ParseTreeNode.Inner): ASTNode.Parameter =
        ASTNode.Parameter(
            name = (node[0] as ParseTreeNode.Leaf).token.content,
            type = transformType(node[4] as ParseTreeNode.Inner)
        )

    private fun transformFunctionParameters(node: ParseTreeNode): List<ASTNode.Parameter> =
        when (node) {
            is ParseTreeNode.EpsilonLeaf -> {
                when (node.type) {
                    Parser.InnerNodeType.FunctionParameterStar -> emptyList()
                    Parser.InnerNodeType.FunctionParameterStarRest -> emptyList()
                    else -> throwInvalidParseError()
                }
            }
            is ParseTreeNode.Inner -> {
                when (node.type) {
                    Parser.InnerNodeType.FunctionParameters -> transformFunctionParameters(node[2])
                    Parser.InnerNodeType.FunctionParameterStar -> {
                        val parameter = transformParameter(node[0] as ParseTreeNode.Inner)
                        listOf(parameter) + transformFunctionParameters(node[2])
                    }
                    Parser.InnerNodeType.FunctionParameterStarRest -> {
                        transformFunctionParameters(node[2])
                    }
                    else -> throwInvalidParseError()
                }
            }
            else -> throwInvalidParseError()
        }


    private fun transformTypeParameters(node: ParseTreeNode): List<Pair<String, ASTNode.Type?>> =
        when (node) {
            is ParseTreeNode.EpsilonLeaf -> {
                when (node.type) {
                    Parser.InnerNodeType.TypeParameters -> emptyList()
                    else -> throwInvalidParseError()
                }
            }
            is ParseTreeNode.Inner -> {
                when (node.type) {
                    Parser.InnerNodeType.TypeParameters -> transformTypeParameters(node[2])
                    Parser.InnerNodeType.TypeParametersNamesPlus -> {
                        val name = (node[0] as ParseTreeNode.Leaf).token.content
                        val parent = transformType(node[2] as ParseTreeNode.Inner)
                        listOf(name to parent) + transformTypeParameters(node[3])
                    }
                    else -> throwInvalidParseError()
                }
            }
            else -> throwInvalidParseError()
        }

    private fun transformTypeArguments(
        node: ParseTreeNode,
        acc: List<ASTNode.TypeArgument> = emptyList()
    ): List<ASTNode.TypeArgument> =
        when (node) {
            is ParseTreeNode.EpsilonLeaf -> {
                when (node.type) {
                    Parser.InnerNodeType.TypeArgumentsOptional,
                    Parser.InnerNodeType.TypeArguments,
                    Parser.InnerNodeType.TypeArgumentsContentRest -> acc
                    else -> throwInvalidParseError()
                }
            }
            is ParseTreeNode.Inner -> {
                when (node.type) {
                    Parser.InnerNodeType.TypeArgumentsOptional -> transformTypeArguments(node[0], acc)
                    Parser.InnerNodeType.TypeArguments -> transformTypeArguments(node[2], acc)
                    Parser.InnerNodeType.TypeArgumentsContent -> {
                        val firstType = transformType(node[0] as ParseTreeNode.Inner)
                        val typeArgument =
                            when (val typeNamedArgumentsOptional = node[2]) {
                                is ParseTreeNode.EpsilonLeaf -> ASTNode.TypeArgument(null, firstType)
                                is ParseTreeNode.Inner -> {
                                    if (firstType is ASTNode.Type.Regular && firstType.typesParameters.isEmpty() && !firstType.nullable)
                                        ASTNode.TypeArgument(
                                            firstType.name,
                                            transformType(typeNamedArgumentsOptional.last() as ParseTreeNode.Inner)
                                        )
                                    else throwInvalidParseError()
                                }
                                else -> throwInvalidParseError()
                            }
                        transformTypeArguments(node.last(), acc + typeArgument)
                    }
                    Parser.InnerNodeType.TypeArgumentsContentRest -> transformTypeArguments(node.last(), acc)
                    else -> throwInvalidParseError()
                }
            }
            else -> throwInvalidParseError()
        }

    private fun transformValDeclaration(rootNode: ParseTreeNode.Inner): ASTNode.ValDeclaration {
        val name = (rootNode[2] as ParseTreeNode.Leaf).token.content
        val valDeclarationRest = rootNode.last() as ParseTreeNode.Inner
        val type = (valDeclarationRest.children.getOrNull(2) as? ParseTreeNode.Inner)?.let(::transformType)
        val value = transformPaddedExpression(valDeclarationRest.last() as ParseTreeNode.Inner)
        return ASTNode.ValDeclaration(name, type, value)
    }

    private fun transformClassDeclaration(rootNode: ParseTreeNode.Inner) =
        ASTNode.ClassDeclaration(
            name = (rootNode[2] as ParseTreeNode.Leaf).token.content,
            typeParameters = transformTypeParameters(rootNode[4]),
            members = transformMembers(rootNode[13]),
            constructorParameters = transformClassProperties(rootNode[8])
        )

    private fun transformClassProperties(currentNode: ParseTreeNode): List<ASTNode.ConstructorParameter> =
        when (currentNode) {
            is ParseTreeNode.EpsilonLeaf -> emptyList()
            is ParseTreeNode.Inner ->
                when (currentNode.type) {
                    Parser.InnerNodeType.ClassPropertyStar ->
                        when (currentNode.children.size) {
                            4 -> listOf(transformClassProperty(currentNode[0] as ParseTreeNode.Inner)) +
                                    transformClassProperties(currentNode.last())
                            2 -> listOf(transformClassProperty(currentNode[0] as ParseTreeNode.Inner))
                            else -> throwInvalidParseError()
                        }
                    else -> throwInvalidParseError()
                }
            else -> throwInvalidParseError()
        }

    private fun transformClassProperty(rootNode: ParseTreeNode.Inner): ASTNode.ConstructorParameter {
        val privateOrPublic = transformPrivateOrPublic(rootNode[0])
        val propertyName = (rootNode[4] as ParseTreeNode.Leaf).token.content
        val propertyType = transformType(rootNode[8] as ParseTreeNode.Inner)
        return ASTNode.ConstructorParameter(
            privateOrPublic,
            propertyName,
            propertyType
        )
    }

    private fun transformMembers(currentNode: ParseTreeNode): List<ASTNode.Member> =
        when (currentNode) {
            is ParseTreeNode.EpsilonLeaf -> emptyList()
            is ParseTreeNode.Inner ->
                when (currentNode.type) {
                    Parser.InnerNodeType.MemberDeclarationStar ->
                        listOf(transformMember(currentNode[0] as ParseTreeNode.Inner)) +
                                transformMembers(currentNode.last())
                    Parser.InnerNodeType.MemberDeclarationStarRest -> transformMembers(currentNode.last())
                    else -> throwInvalidParseError()
                }
            else -> throwInvalidParseError()
        }

    private fun transformMember(rootNode: ParseTreeNode.Inner): ASTNode.Member {
        val privateOrPublic = transformPrivateOrPublic(rootNode[0])
        val functionDeclarationOrValDeclarationNode = rootNode[2][0] as ParseTreeNode.Inner
        val declaration: ASTNode.FunctionDeclarationOrValDeclaration =
            when (functionDeclarationOrValDeclarationNode.type) {
                Parser.InnerNodeType.ValDeclaration -> transformValDeclaration(functionDeclarationOrValDeclarationNode)
                Parser.InnerNodeType.FunctionDeclaration ->
                    transformFunctionDeclaration(functionDeclarationOrValDeclarationNode)
                else -> throwInvalidParseError(functionDeclarationOrValDeclarationNode.type.name)
            }
        return ASTNode.Member(privateOrPublic, declaration)
    }

    private fun transformPrivateOrPublic(rootNode: ParseTreeNode) =
        ASTNode.VisibilityModifier.values()
            .find { (rootNode[0] as ParseTreeNode.Leaf).token.content == it.name.toLowerCase() }
            ?: throwInvalidParseError()

    private fun transformIf(rootNode: ParseTreeNode.Inner): ASTNode.If {
        val condition = transformParenthesizedExpression(rootNode[2] as ParseTreeNode.Inner)
        val positiveBranch = transformBlockOrStatement(rootNode[4] as ParseTreeNode.Inner)
        val negativeBranch =
            if (rootNode.children.size == 7) transformElse(rootNode[6] as ParseTreeNode.Inner).negativeBranch
            else null
        return ASTNode.If.Statement(
            condition,
            (positiveBranch as? ASTNode.Block)?.toBlockWithoutValue() ?: positiveBranch,
            (negativeBranch as? ASTNode.Block)?.toBlockWithoutValue() ?: negativeBranch
        ).let { it.asExpression() ?: it }
    }

    private fun transformElse(rootNode: ParseTreeNode.Inner): ASTNode.If.NegativeBranchOnly {
        val negativeBranch = transformBlockOrStatement(rootNode.last() as ParseTreeNode.Inner)
        return ASTNode.If.NegativeBranchOnly(negativeBranch)
    }

    private fun transformBlockOrStatement(rootNode: ParseTreeNode.Inner): ASTNode.Statement {
        val child = rootNode[0] as ParseTreeNode.Inner
        return when (child.type) {
            Parser.InnerNodeType.Block -> transformBlock(child)
            Parser.InnerNodeType.Statement -> transformStatement(child)
            else -> throwInvalidParseError()
        }
    }

    private fun transformBlock(rootNode: ParseTreeNode.Inner): ASTNode.Block {
        val statements = transformProgram(rootNode[1] as ParseTreeNode.Inner)
        val returnValue = statements.lastOrNull()
        return if (returnValue is ASTNode.Expression)
            ASTNode.Block.WithValue(
                ASTNode.Statements(
                    statements.dropLast(1)
                ),
                returnValue
            )
        else
            ASTNode.Block.WithoutValue(statements)
    }

    private fun transformLambdaParameters(node: ParseTreeNode): List<Pair<String, ASTNode.Type>> =
        when (node) {
            is ParseTreeNode.EpsilonLeaf ->
                when (node.type) {
                    Parser.InnerNodeType.LambdaParametersStar,
                    Parser.InnerNodeType.LambdaParametersRest -> emptyList()
                    else -> throwInvalidParseError()
                }
            is ParseTreeNode.Inner ->
                when (node.type) {
                    Parser.InnerNodeType.LambdaParametersStar -> {
                        val parameterName = (node[0] as ParseTreeNode.Leaf).token.content
                        val parameterType = transformType(node[4] as ParseTreeNode.Inner)
                        listOf(parameterName to parameterType) + transformLambdaParameters(node.last())
                    }
                    Parser.InnerNodeType.LambdaParametersRest -> transformLambdaParameters(node.last())
                    else -> throwInvalidParseError()
                }
            else -> throwInvalidParseError()
        }

    private fun transformLambda(rootNode: ParseTreeNode.Inner): ASTNode.Lambda {
        val statements = transformProgram(rootNode[6] as ParseTreeNode.Inner)
        val parameters = transformLambdaParameters(rootNode[3])
        val returnValue = statements.lastOrNull()
        return if (returnValue is ASTNode.Expression)
            ASTNode.Lambda(parameters, ASTNode.Statements(statements.dropLast(1)), returnValue)
        else
            ASTNode.Lambda(parameters, ASTNode.Statements(statements), ASTNode.Unit)
    }

    private fun transformParenthesizedExpression(rootNode: ParseTreeNode.Inner): ASTNode.Expression =
        transformExpression(rootNode.children[2] as ParseTreeNode.Inner)

    private fun transformExpression(rootNode: ParseTreeNode.Inner): ASTNode.Expression {
        val singleChild =
            rootNode.children.singleOrNull() as? ParseTreeNode.Inner
                ?: throwInvalidParseError("rootNode.children: ${rootNode.children.joinToString {
                    (it as? ParseTreeNode.Inner)?.type?.name ?: (it as? ParseTreeNode.Leaf)?.token?.type?.name
                    ?: "Epsilon"
                }}")
        return when (singleChild.type) {
            Parser.InnerNodeType.ElvisExpression -> transformElvisExpression(singleChild)
            Parser.InnerNodeType.IfExpression ->
                transformIf(singleChild) as? ASTNode.If.Expression
                    ?: throwInvalidParseError("Got If statement instead of if expression")
            Parser.InnerNodeType.ElseExpression -> transformElse(singleChild)
            else -> throwInvalidParseError("singleChild.type is ${singleChild.type}")
        }
    }

    private fun transformExpressionOrStatement(rootNode: ParseTreeNode.Inner): ASTNode.Statement {
        val singleChild =
            rootNode.children.singleOrNull() as? ParseTreeNode.Inner
                ?: throwInvalidParseError("rootNode.children: ${rootNode.children.joinToString {
                    (it as? ParseTreeNode.Inner)?.type?.name ?: (it as? ParseTreeNode.Leaf)?.token?.type?.name
                    ?: "Epsilon"
                }}")
        return when (singleChild.type) {
            Parser.InnerNodeType.ElvisExpression -> transformElvisExpression(singleChild)
            Parser.InnerNodeType.IfExpression -> transformIf(singleChild)
            Parser.InnerNodeType.ElseExpression -> transformElse(singleChild)
            else -> throwInvalidParseError("singleChild.type is ${singleChild.type}")
        }
    }

    private fun transformPaddedExpression(rootNode: ParseTreeNode.Inner): ASTNode.Expression =
        transformExpression(rootNode[1] as ParseTreeNode.Inner)

    private fun ASTNode.BinaryExpression<*, *>.rotated(): ASTNode.Expression =
        when (right) {
            is ASTNode.BinaryExpression<*, *> -> {
                val binaryExpression = right as ASTNode.BinaryExpression<*, *>
                if (binaryExpression.operator.group == this.operator.group)
                    ASTNode.BinaryExpression(
                        left = ASTNode.BinaryExpression(left, operator, binaryExpression.left).rotated(),
                        operator = binaryExpression.operator,
                        right = binaryExpression.right
                    )
                else this
            }
            is ASTNode.Invocation ->
                if (this.operator.group == ASTNode.BinaryOperator.Group.MemberAccess) {
                    val invocation = right as ASTNode.Invocation
                    ASTNode.Invocation(
                        function = ASTNode.BinaryExpression(left, this.operator, invocation.function).rotated(),
                        typeArguments = invocation.typeArguments,
                        arguments = invocation.arguments
                    )
                } else this
            else -> this
        }

    private fun ASTNode.InfixExpression<*, *>.rotated(): ASTNode.InfixExpression<*, *> =
        when (right) {
            is ASTNode.InfixExpression<*, *> -> {
                val infixExpression = right as ASTNode.InfixExpression<*, *>
                if (infixExpression.function == this.function)
                    ASTNode.InfixExpression(
                        left = ASTNode.InfixExpression(left, function, infixExpression.left).rotated(),
                        function = function,
                        right = infixExpression.right
                    )
                else this
            }
            else -> this
        }

    private fun transformElvisExpression(rootNode: ParseTreeNode.Inner): ASTNode.Expression =
        if (rootNode.children.size == 2)
            transformReturnExpression(rootNode[0] as ParseTreeNode.Inner)
        else
            ASTNode.BinaryExpression(
                transformReturnExpression(rootNode[0] as ParseTreeNode.Inner),
                ASTNode.BinaryOperator.Elvis,
                transformElvisExpression(rootNode.last() as ParseTreeNode.Inner)
            ).rotated()

    private fun transformReturnExpression(rootNode: ParseTreeNode.Inner): ASTNode.Expression =
        when (rootNode.children.size) {
            1 -> transformInfixExpression(rootNode[0] as ParseTreeNode.Inner)
            3 -> ASTNode.Return(transformReturnExpression(rootNode.last() as ParseTreeNode.Inner))
            else -> throwInvalidParseError()
        }

    private fun transformInfixExpression(rootNode: ParseTreeNode.Inner): ASTNode.Expression =
        if (rootNode.children.size == 2)
            transformDisjunctionExpression(rootNode[0] as ParseTreeNode.Inner)
        else {
            ASTNode.InfixExpression(
                transformDisjunctionExpression(rootNode[0] as ParseTreeNode.Inner),
                (rootNode[2] as ParseTreeNode.Leaf).token.content,
                transformInfixExpression(rootNode.last() as ParseTreeNode.Inner)
            ).rotated()
        }

    private fun transformDisjunctionExpression(rootNode: ParseTreeNode.Inner): ASTNode.Expression =
        if (rootNode.children.size == 2)
            transformConjunctionExpression(rootNode[0] as ParseTreeNode.Inner)
        else
            ASTNode.BinaryExpression(
                transformConjunctionExpression(rootNode[0] as ParseTreeNode.Inner),
                ASTNode.BinaryOperator.Or,
                transformDisjunctionExpression(rootNode.last() as ParseTreeNode.Inner)
            ).rotated()

    private fun transformConjunctionExpression(rootNode: ParseTreeNode.Inner): ASTNode.Expression =
        if (rootNode.children.size == 2)
            transformEqualityExpression(rootNode[0] as ParseTreeNode.Inner)
        else
            ASTNode.BinaryExpression(
                transformEqualityExpression(rootNode[0] as ParseTreeNode.Inner),
                ASTNode.BinaryOperator.And,
                transformConjunctionExpression(rootNode.last() as ParseTreeNode.Inner)
            ).rotated()

    private fun transformEqualityExpression(rootNode: ParseTreeNode.Inner): ASTNode.Expression =
        if (rootNode.children.size == 2)
            transformComparisonExpression(rootNode[0] as ParseTreeNode.Inner)
        else {
            ASTNode.BinaryExpression(
                transformComparisonExpression(rootNode[0] as ParseTreeNode.Inner),
                when ((rootNode[2][0] as ParseTreeNode.Leaf).token.type) {
                    TokenType.Equal -> ASTNode.BinaryOperator.Equal
                    TokenType.NotEqual -> ASTNode.BinaryOperator.NotEqual
                    else -> throwInvalidParseError()
                },
                transformEqualityExpression(rootNode.last() as ParseTreeNode.Inner)
            ).rotated()
        }

    private fun transformComparisonExpression(rootNode: ParseTreeNode.Inner): ASTNode.Expression =
        if (rootNode.children.size == 2)
            transformAdditiveExpression(rootNode[0] as ParseTreeNode.Inner)
        else {
            ASTNode.BinaryExpression(
                transformAdditiveExpression(rootNode[0] as ParseTreeNode.Inner),
                when ((rootNode[2][0] as ParseTreeNode.Leaf).token.type) {
                    TokenType.OpenBrokets -> ASTNode.BinaryOperator.LessThan
                    TokenType.CloseBrokets -> ASTNode.BinaryOperator.GreaterThan
                    TokenType.LessThanEqual -> ASTNode.BinaryOperator.LessThanEqual
                    TokenType.GreaterThanEqual -> ASTNode.BinaryOperator.GreaterThanEqual
                    else -> throwInvalidParseError()
                },
                transformComparisonExpression(rootNode.last() as ParseTreeNode.Inner)
            ).rotated()
        }

    private fun transformAdditiveExpression(rootNode: ParseTreeNode.Inner): ASTNode.Expression =
        if (rootNode.children.size == 2)
            transformMultiplicativeExpression(rootNode[0] as ParseTreeNode.Inner)
        else {
            ASTNode.BinaryExpression(
                transformMultiplicativeExpression(rootNode[0] as ParseTreeNode.Inner),
                when ((rootNode[2][0] as ParseTreeNode.Leaf).token.type) {
                    TokenType.Plus -> ASTNode.BinaryOperator.Plus
                    TokenType.Minus -> ASTNode.BinaryOperator.Minus
                    else -> throwInvalidParseError()
                },
                transformAdditiveExpression(rootNode.last() as ParseTreeNode.Inner)
            ).rotated()
        }

    private fun transformMultiplicativeExpression(rootNode: ParseTreeNode.Inner): ASTNode.Expression =
        if (rootNode.children.size == 2)
            transformMemberAccess(rootNode[0] as ParseTreeNode.Inner)
        else {
            ASTNode.BinaryExpression(
                transformMemberAccess(rootNode[0] as ParseTreeNode.Inner),
                when ((rootNode[2][0] as ParseTreeNode.Leaf).token.type) {
                    TokenType.Percentage -> ASTNode.BinaryOperator.Modulo
                    TokenType.Star -> ASTNode.BinaryOperator.Times
                    TokenType.Division -> ASTNode.BinaryOperator.Division
                    else -> throwInvalidParseError()
                },
                transformMultiplicativeExpression(rootNode.last() as ParseTreeNode.Inner)
            ).rotated()
        }

    private fun transformInvocationArguments(node: ParseTreeNode): List<ASTNode.FunctionArgument> =
        when (node) {
            is ParseTreeNode.EpsilonLeaf -> {
                when (node.type) {
                    Parser.InnerNodeType.ArgumentStar,
                    Parser.InnerNodeType.ArgumentStarRest -> emptyList()
                    else -> throwInvalidParseError()
                }
            }
            is ParseTreeNode.Inner -> {
                when (node.type) {
                    Parser.InnerNodeType.InvocationArguments -> transformInvocationArguments(node[2])
                    Parser.InnerNodeType.ArgumentStar -> {
                        val isNamedArgument = node[2] is ParseTreeNode.Inner
                        val functionArgument =
                            if (isNamedArgument)
                                ASTNode.FunctionArgument(
                                    name = (transformExpression(node[0] as ParseTreeNode.Inner) as ASTNode.Identifier).value,
                                    value = transformExpression(node[2][2] as ParseTreeNode.Inner)
                                )
                            else
                                ASTNode.FunctionArgument(
                                    name = null,
                                    value = transformExpression(node[0] as ParseTreeNode.Inner)
                                )

                        listOf(functionArgument) + transformInvocationArguments(node.last())
                    }
                    Parser.InnerNodeType.ArgumentStarRest -> transformInvocationArguments(node.last())
                    else -> throwInvalidParseError()
                }
            }
            else -> throwInvalidParseError()
        }

    private fun transformInvocation(rootNode: ParseTreeNode.Inner): ASTNode.Expression {
        val simpleOrParenthesizedExpression = (rootNode[0] as ParseTreeNode.Inner)[0] as ParseTreeNode.Inner
        val maybeFunction = when (simpleOrParenthesizedExpression.type) {
            Parser.InnerNodeType.SimpleExpression ->
                transformSimpleExpression(simpleOrParenthesizedExpression)
            Parser.InnerNodeType.ParenthesizedExpression ->
                transformParenthesizedExpression(simpleOrParenthesizedExpression)
            else -> throwInvalidParseError("type: ${simpleOrParenthesizedExpression.type}")
        }

        fun getFunctionArguments(
            node: ParseTreeNode
        ): List<Pair<List<ASTNode.TypeArgument>, List<ASTNode.FunctionArgument>>> =
            when (node) {
                is ParseTreeNode.EpsilonLeaf -> {
                    when (node.type) {
                        Parser.InnerNodeType.InvocationArgumentsStar -> emptyList()
                        else -> throwInvalidParseError()
                    }
                }
                is ParseTreeNode.Inner -> {
                    when (node.type) {
                        Parser.InnerNodeType.InvocationArgumentsStar -> {
                            val arguments = if (node.children.size == 4)
                                transformTypeArguments(node[0]) to transformInvocationArguments(node[1])
                            else
                                emptyList<ASTNode.TypeArgument>() to transformInvocationArguments(node[0])
                            listOf(arguments) + getFunctionArguments(node.last())
                        }
                        else -> throwInvalidParseError()
                    }
                }
                else -> throwInvalidParseError()
            }
        return getFunctionArguments(rootNode[2]).fold(maybeFunction) { acc, arguments ->
            ASTNode.Invocation(
                function = acc,
                typeArguments = arguments.first,
                arguments = arguments.second
            )
        }
    }

    private fun transformMemberAccess(rootNode: ParseTreeNode.Inner): ASTNode.Expression {
        val member = transformInvocation(rootNode[0] as ParseTreeNode.Inner)

        return if (rootNode.children.size == 2)
            member
        else {
            ASTNode.BinaryExpression(
                member,
                when ((rootNode[2][0] as ParseTreeNode.Leaf).token.type) {
                    TokenType.Dot -> ASTNode.BinaryOperator.Dot
                    TokenType.NullAwareDot -> ASTNode.BinaryOperator.NullAwareDot
                    else -> throwInvalidParseError()
                },
                transformMemberAccess(rootNode.last() as ParseTreeNode.Inner)
            ).also { assert(it.right is ASTNode.Identifier || it.right is ASTNode.Invocation) }.rotated()
        }
    }

    private fun transformSimpleExpression(rootNode: ParseTreeNode.Inner): ASTNode.Expression {
        val firstChild = rootNode[0]
        return if (firstChild is ParseTreeNode.Inner)
            when (firstChild.type) {
                Parser.InnerNodeType.Number -> transformNumber(firstChild)
                Parser.InnerNodeType.BooleanLiteral -> transformBooleanLiteral(firstChild)
                Parser.InnerNodeType.StringLiteral -> transformStringLiteral(firstChild)
                Parser.InnerNodeType.Lambda -> transformLambda(firstChild)
                else -> throwInvalidParseError("firstChild.type is ${firstChild.type}")
            } else if (firstChild is ParseTreeNode.Leaf && firstChild.token.type == TokenType.SimpleName)
            ASTNode.Identifier(firstChild.token.content)
        else throwInvalidParseError()
    }

    private fun transformNumber(rootNode: ParseTreeNode.Inner): ASTNode.Expression {
        val firstTokenContent = (rootNode[0] as ParseTreeNode.Leaf).token.content
        return if (
            rootNode.children.size == 1 ||
            rootNode.children.size == 2 &&
            (rootNode[0] as? ParseTreeNode.Leaf)?.token?.type == TokenType.DecimalLiteral &&
            (rootNode[1] as? ParseTreeNode.Inner)?.type == Parser.InnerNodeType.WhitespacePlus
        ) {
            ASTNode.IntLiteral(firstTokenContent.toInt())
        } else {
            when (val lastNode = rootNode.last()) {
                is ParseTreeNode.Leaf -> ASTNode.FloatLiteral(
                    "$firstTokenContent.${lastNode.token.content}".toFloat()
                )
                is ParseTreeNode.Inner -> {
                    val member = ASTNode.IntLiteral(firstTokenContent.toInt())
                    val propertyName = ASTNode.Identifier((lastNode[1] as ParseTreeNode.Leaf).token.content)
                    ASTNode.BinaryExpression(
                        member,
                        ASTNode.BinaryOperator.Dot,
                        propertyName
                    )
                }
                else -> throwInvalidParseError()
            }
        }
    }

    private fun transformBooleanLiteral(rootNode: ParseTreeNode.Inner) =
        ASTNode.BooleanLiteral(
            when ((rootNode[0] as ParseTreeNode.Leaf).token.content) {
                Keyword.True.asString -> true
                Keyword.False.asString -> false
                else -> throwInvalidParseError()
            }
        )

    private fun transformStringLiteral(rootNode: ParseTreeNode.Inner): ASTNode.StringLiteral {
        fun getStringFromParseTreeNodes(currentNode: ParseTreeNode.Inner): String =
            when (currentNode.children.size) {
                1 -> ""
                2 -> (currentNode[0] as ParseTreeNode.Leaf).token.content +
                        getStringFromParseTreeNodes(currentNode.last() as ParseTreeNode.Inner)
                else -> throwInvalidParseError()
            }

        return ASTNode.StringLiteral(
            getStringFromParseTreeNodes(rootNode.last() as ParseTreeNode.Inner)
        )
    }


    private fun assertSemanticChecks(statements: ASTNode.Statements) {
        assertNoIfNegativeBranch(statements)
    }

    private fun assertNoIfNegativeBranch(statements: ASTNode.Statements) {
        assert(statements.all { it !is ASTNode.If.NegativeBranchOnly })
    }

    private operator fun ParseTreeNode.get(index: Int) =
        (this as ParseTreeNode.Inner).children[index]

    private fun ParseTreeNode.last() =
        (this as ParseTreeNode.Inner).children.last()

    private fun throwInvalidParseError(message: String? = null): Nothing =
        throw CompilationError("Invalid parse error" + message?.let { " $it" }.orEmpty())

}
