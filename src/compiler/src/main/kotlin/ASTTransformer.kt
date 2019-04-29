package com.godel.compiler

import java.lang.RuntimeException

object ASTTransformer {
    fun transformAST(rootNode: ParseTreeNode) =
        when (rootNode) {
            is ParseTreeNode.Inner -> transformProgram(rootNode)
            else -> throw CompilationError("Cannot transform AST from a tree whose root isn't a statements node.")
        }

    private fun transformProgram(rootNode: ParseTreeNode.Inner) =
        transformStatements(rootNode[1] as ParseTreeNode.Inner)

    private fun transformStatements(rootNode: ParseTreeNode.Inner): ASTNode.Statements {
        fun getStatementsParseTreeNodes(currentNode: ParseTreeNode): List<ParseTreeNode.Inner> =
            (currentNode as? ParseTreeNode.Inner)?.let { innerNode ->
                requireByGrammar(innerNode.type == Parser.InnerNodeType.Statements)
                listOfNotNull(innerNode[0] as? ParseTreeNode.Inner) +
                        (innerNode.children.getOrNull(2) as? ParseTreeNode.Inner)
                            ?.takeIf { it.type == Parser.InnerNodeType.Statements }
                            ?.let { getStatementsParseTreeNodes(it) }.orEmpty()
            }.orEmpty()
        return ASTNode.Statements(
            getStatementsParseTreeNodes(rootNode).map(::transformStatement)
        )
    }

    private fun transformStatement(rootNode: ParseTreeNode.Inner): ASTNode.Statement {
        val firstChild = rootNode[0] as ParseTreeNode.Inner
        return when (firstChild.type) {
            Parser.InnerNodeType.Expression -> transformExpression(firstChild)
            Parser.InnerNodeType.Declaration -> transformDeclaration(firstChild)
            Parser.InnerNodeType.IfExpression -> transformIf(firstChild)
            else -> throwInvalidParseError("firstChild.type is ${rootNode.type}")
        }
    }

    private fun transformDeclaration(rootNode: ParseTreeNode.Inner): ASTNode.Statement {
        val firstChild = rootNode[0] as ParseTreeNode.Inner
        return when (firstChild.type) {
            Parser.InnerNodeType.ValDeclaration -> transformValDeclaration(firstChild)
            Parser.InnerNodeType.ClassDeclaration -> transformClassDeclaration(firstChild)
            else -> throwInvalidParseError("firstChild.type is ${rootNode.type}")
        }
    }

    private fun getUnderscoresFromUnderscoreStar(node: ParseTreeNode): String =
        when (node) {
            is ParseTreeNode.Inner ->
                "_" + getUnderscoresFromUnderscoreStar(node[1])
            is ParseTreeNode.EpsilonLeaf -> ""
            else -> throwInvalidParseError()
        }

    private fun transformType(node: ParseTreeNode.Inner) =
        ASTNode.Type(
            name = (node[0] as ParseTreeNode.Leaf).token.content,
            typesParameters = transformTypeParameters(node[1]),
            nullable = node.children[2] is ParseTreeNode.Inner
        )

    private fun transformTypeParameters(node: ParseTreeNode): Map<String, ASTNode.Type?> =
        when (node) {
            is ParseTreeNode.EpsilonLeaf -> {
                when (node.type) {
                    Parser.InnerNodeType.TypeParametersNamesPlus,
                    Parser.InnerNodeType.TypeParameters -> emptyMap()
                    else -> throwInvalidParseError()
                }
            }
            is ParseTreeNode.Inner -> {
                when (node.type) {
                    Parser.InnerNodeType.TypeParameters -> transformTypeParameters(node[2])
                    Parser.InnerNodeType.TypeParametersNamesPlus -> {
                        val name = (node[0] as ParseTreeNode.Leaf).token.content
                        val parent = transformType(node[2] as ParseTreeNode.Inner)
                        mapOf(name to parent) + transformTypeParameters(node[3])
                    }
                    else -> throwInvalidParseError()
                }
            }
            else -> throwInvalidParseError()
        }

    private fun transformValDeclaration(rootNode: ParseTreeNode.Inner): ASTNode.ValDeclaration {
        val underscores = getUnderscoresFromUnderscoreStar(rootNode[2])
        val name = underscores + (rootNode[3] as ParseTreeNode.Leaf).token.content
        val valDeclarationRest = rootNode.last() as ParseTreeNode.Inner
        val type = (valDeclarationRest.children.getOrNull(2) as? ParseTreeNode.Inner)?.let(::transformType)
        val value = transformPaddedExpression(valDeclarationRest.last() as ParseTreeNode.Inner)
        return ASTNode.ValDeclaration(name, type, value)
    }

    private fun transformClassDeclaration(rootNode: ParseTreeNode.Inner): ASTNode.Statement =
        TODO()

    private fun transformIf(rootNode: ParseTreeNode.Inner): ASTNode.If {
        val condition = transformParenthesizedExpression(rootNode[2] as ParseTreeNode.Inner)
        val positiveBranch = transformBlockOrStatement(rootNode[4] as ParseTreeNode.Inner)
        val negativeBranch = when (val ifExpressionRest = rootNode.last()) {
            is ParseTreeNode.EpsilonLeaf -> null
            is ParseTreeNode.Inner -> transformBlockOrStatement(ifExpressionRest)
            else -> throwInvalidParseError()
        }
        return if (positiveBranch is ASTNode.Block.WithValue && negativeBranch is ASTNode.Block.WithValue)
            ASTNode.If.Expression(condition, positiveBranch, negativeBranch)
        else
            ASTNode.If.Statement(condition, positiveBranch, negativeBranch)
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
        val statements = transformStatements(rootNode[1] as ParseTreeNode.Inner)
        val returnValue = statements.statements.last()
        return if (returnValue is ASTNode.Expression)
            ASTNode.Block.WithValue(
                ASTNode.Statements(
                    statements.statements.dropLast(1)
                ),
                returnValue
            )
        else
            ASTNode.Block.WithoutValue(statements)
    }

    private fun transformParenthesizedExpression(rootNode: ParseTreeNode.Inner): ASTNode.Expression =
        transformExpression(rootNode.children[3] as ParseTreeNode.Inner)

    private fun transformExpression(rootNode: ParseTreeNode.Inner): ASTNode.Expression {
        val singleChild =
            rootNode.children.singleOrNull() as? ParseTreeNode.Inner
                ?: throwInvalidParseError("rootNode.children: ${rootNode.children.joinToString {
                    (it as? ParseTreeNode.Inner)?.type?.name ?: (it as? ParseTreeNode.Leaf)?.token?.type?.name
                    ?: "Epsilon"
                }}")
        return when (singleChild.type) {
            Parser.InnerNodeType.ElvisExpression -> transformElvisExpression(singleChild)
            else -> throwInvalidParseError("singleChild.type is ${singleChild.type}")
        }
    }

    private fun transformPaddedExpression(rootNode: ParseTreeNode.Inner): ASTNode.Expression =
        transformExpression(rootNode[1] as ParseTreeNode.Inner)

    private fun transformElvisExpression(rootNode: ParseTreeNode.Inner): ASTNode.Expression =
        if (rootNode.last() is ParseTreeNode.EpsilonLeaf)
            transformDisjunctionExpression(rootNode[0] as ParseTreeNode.Inner)
        else
            ASTNode.BinaryExpression(
                transformDisjunctionExpression(rootNode[0] as ParseTreeNode.Inner),
                ASTNode.BinaryOperator.Elvis,
                transformElvisExpression((rootNode.last() as ParseTreeNode.Inner).last() as ParseTreeNode.Inner)
            )

    private fun transformDisjunctionExpression(rootNode: ParseTreeNode.Inner): ASTNode.Expression =
        if (rootNode.last() is ParseTreeNode.EpsilonLeaf)
            transformConjunctionExpression(rootNode[0] as ParseTreeNode.Inner)
        else
            ASTNode.BinaryExpression(
                transformConjunctionExpression(rootNode[0] as ParseTreeNode.Inner),
                ASTNode.BinaryOperator.Or,
                transformDisjunctionExpression((rootNode.last() as ParseTreeNode.Inner).last() as ParseTreeNode.Inner)
            )

    private fun transformConjunctionExpression(rootNode: ParseTreeNode.Inner): ASTNode.Expression =
        if (rootNode.last() is ParseTreeNode.EpsilonLeaf)
            transformEqualityExpression(rootNode[0] as ParseTreeNode.Inner)
        else
            ASTNode.BinaryExpression(
                transformEqualityExpression(rootNode[0] as ParseTreeNode.Inner),
                ASTNode.BinaryOperator.And,
                transformConjunctionExpression((rootNode.last() as ParseTreeNode.Inner).last() as ParseTreeNode.Inner)
            )

    private fun transformEqualityExpression(rootNode: ParseTreeNode.Inner): ASTNode.Expression =
        if (rootNode.last() is ParseTreeNode.EpsilonLeaf)
            transformComparisonExpression(rootNode[0] as ParseTreeNode.Inner)
        else
            ASTNode.BinaryExpression(
                transformComparisonExpression(rootNode[0] as ParseTreeNode.Inner),
                when (((rootNode[1] as ParseTreeNode.Inner)[0] as ParseTreeNode.Leaf).token.type) {
                    TokenType.Assignment -> ASTNode.BinaryOperator.Equal
                    TokenType.ExclamationMark -> ASTNode.BinaryOperator.NotEqual
                    else -> throwInvalidParseError()
                },
                transformEqualityExpression((rootNode.last() as ParseTreeNode.Inner).last() as ParseTreeNode.Inner)
            )

    private fun transformComparisonExpression(rootNode: ParseTreeNode.Inner): ASTNode.Expression =
        if (rootNode.last() is ParseTreeNode.EpsilonLeaf)
            transformAdditiveExpression(rootNode[0] as ParseTreeNode.Inner)
        else {
            val operator = rootNode[1] as ParseTreeNode.Inner
            ASTNode.BinaryExpression(
                transformAdditiveExpression(rootNode[0] as ParseTreeNode.Inner),
                when {
                    operator.children.size == 1 && (operator[0] as ParseTreeNode.Leaf).token.type == TokenType.OpenBrokets -> ASTNode.BinaryOperator.LessThan
                    operator.children.size == 1 && (operator[0] as ParseTreeNode.Leaf).token.type == TokenType.CloseBraces -> ASTNode.BinaryOperator.GreaterThan
                    operator.children.size == 2 && (operator[0] as ParseTreeNode.Leaf).token.type == TokenType.OpenBrokets -> ASTNode.BinaryOperator.LessThanEqual
                    operator.children.size == 2 && (operator[0] as ParseTreeNode.Leaf).token.type == TokenType.CloseBraces -> ASTNode.BinaryOperator.GreaterThanEqual
                    else -> throwInvalidParseError()
                },
                transformComparisonExpression((rootNode.last() as ParseTreeNode.Inner).last() as ParseTreeNode.Inner)
            )
        }

    private fun transformAdditiveExpression(rootNode: ParseTreeNode.Inner): ASTNode.Expression =
        if (rootNode.last() is ParseTreeNode.EpsilonLeaf)
            transformMultiplicativeExpression(rootNode[0] as ParseTreeNode.Inner)
        else
            ASTNode.Addition(
                transformMultiplicativeExpression(rootNode[0] as ParseTreeNode.Inner),
                transformAdditiveExpression((rootNode.last() as ParseTreeNode.Inner).last() as ParseTreeNode.Inner)
            )

    private fun transformMultiplicativeExpression(rootNode: ParseTreeNode.Inner): ASTNode.Expression =
        if (rootNode.last() is ParseTreeNode.EpsilonLeaf)
            transformInvocation(rootNode[0] as ParseTreeNode.Inner)
        else
            ASTNode.Multiplication(
                transformInvocation(rootNode[0] as ParseTreeNode.Inner),
                transformMultiplicativeExpression((rootNode.last() as ParseTreeNode.Inner).last() as ParseTreeNode.Inner)
            )

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
                    Parser.InnerNodeType.ArgumentStar ->
                        listOf(
                            ASTNode.FunctionArgument(
                                name = null,
                                value = transformExpression(node[0] as ParseTreeNode.Inner)
                            )
                        )
                    Parser.InnerNodeType.ArgumentStarRest -> transformInvocationArguments(node.last())
                    else -> throwInvalidParseError()
                }
            }
            else -> throwInvalidParseError()
        }

    private fun transformInvocation(rootNode: ParseTreeNode.Inner): ASTNode.Expression {
        val maybeFunction = transformMemberAccess(rootNode[0] as ParseTreeNode.Inner)

        fun getFunctionArguments(node: ParseTreeNode): List<List<ASTNode.FunctionArgument>> =
            when (node) {
                is ParseTreeNode.EpsilonLeaf -> {
                    when (node.type) {
                        Parser.InnerNodeType.InvocationArgumentsStar -> emptyList()
                        else -> throwInvalidParseError()
                    }
                }
                is ParseTreeNode.Inner -> {
                    when (node.type) {
                        Parser.InnerNodeType.InvocationArgumentsStar ->
                            listOf(transformInvocationArguments(node[1])) + getFunctionArguments(node.last())
                        else -> throwInvalidParseError()
                    }
                }
                else -> throwInvalidParseError()
            }
        return getFunctionArguments(rootNode[2]).fold(maybeFunction) { acc, functionArguments ->
            ASTNode.Invocation(
                function = acc,
                arguments = functionArguments
            )
        }
    }

    private fun transformMemberAccess(rootNode: ParseTreeNode.Inner): ASTNode.Expression {
        val simpleOrParenthesizedExpression = (rootNode[0] as ParseTreeNode.Inner)[0] as ParseTreeNode.Inner
        val member = when (simpleOrParenthesizedExpression.type) {
            Parser.InnerNodeType.SimpleExpression ->
                transformSimpleExpression(simpleOrParenthesizedExpression)
            Parser.InnerNodeType.ParenthesizedExpression ->
                transformParenthesizedExpression(simpleOrParenthesizedExpression)
            else -> throwInvalidParseError()
        }

        return if (rootNode.last() is ParseTreeNode.EpsilonLeaf)
            member
        else
            member
    }

    private fun transformSimpleExpression(rootNode: ParseTreeNode.Inner): ASTNode.Expression {
        val firstChild = rootNode[0]
        return if (firstChild is ParseTreeNode.Inner)
            when (firstChild.type) {
                Parser.InnerNodeType.Number -> transformNumber(firstChild)
                Parser.InnerNodeType.BooleanLiteral -> transformBooleanLiteral(firstChild)
                Parser.InnerNodeType.StringLiteral -> transformStringLiteral(firstChild)
                else -> throwInvalidParseError("firstChild.type is ${firstChild.type}")
            } else if (firstChild is ParseTreeNode.Leaf && firstChild.token.type == TokenType.SimpleName)
            ASTNode.Identifier(firstChild.token.content)
        else throwInvalidParseError()
    }

    private fun transformNumber(rootNode: ParseTreeNode.Inner): ASTNode.Expression {
        val firstTokenContent = (rootNode[0] as ParseTreeNode.Leaf).token.content
        return when (val numberRest = rootNode.last()) {
            is ParseTreeNode.EpsilonLeaf -> ASTNode.IntLiteral(firstTokenContent.toInt())
            is ParseTreeNode.Inner -> {
                when (val decimalOrMemberAccess = (numberRest.last() as ParseTreeNode.Inner)[0]) {
                    is ParseTreeNode.Leaf -> ASTNode.FloatLiteral(
                        "$firstTokenContent.${decimalOrMemberAccess.token.content}".toFloat()
                    )
                    is ParseTreeNode.Inner -> {
                        val member = ASTNode.IntLiteral(firstTokenContent.toInt())
                        val propertyName = (decimalOrMemberAccess[1] as ParseTreeNode.Leaf).token.content
                        ASTNode.MemberAccess(member, false, propertyName)
                    }
                    else -> throwInvalidParseError()
                }
            }
            else -> throwInvalidParseError()
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
                2 -> (currentNode[0][0] as ParseTreeNode.Leaf).token.content +
                        getStringFromParseTreeNodes(currentNode.last() as ParseTreeNode.Inner)
                else -> throwInvalidParseError()
            }

        return ASTNode.StringLiteral(
            getStringFromParseTreeNodes(rootNode.last() as ParseTreeNode.Inner)
        )
    }

    private fun requireByGrammar(value: Boolean, message: String? = null) =
        require(value) {
            "Invalid parse error${message?.let { ": $it" }.orEmpty()}."
        }

    private operator fun ParseTreeNode.get(index: Int) =
        (this as ParseTreeNode.Inner).children[index]

    private fun ParseTreeNode.last() =
        (this as ParseTreeNode.Inner).children.last()

    private fun throwInvalidParseError(message: String? = null): Nothing =
        throw RuntimeException("Invalid parse error" + message?.let { " $it" }.orEmpty())
//
//    private fun transformASTFromNumber(rootNode: ParseTreeNode.Inner): ASTNode.Expression {
//        check(rootNode.children.size == 2)
//        val leftSideNumberString = (rootNode.children[0] as ParseTreeNode.Leaf).token.content
//        val numberRest = (rootNode.children[1] as ParseTreeNode.Inner)
//        return if (numberRest.children.size == 2) {
//            val rightSideNumberString = (numberRest.last() as ParseTreeNode.Leaf).token.content
//            ASTNode.FloatLiteral("$leftSideNumberString.$rightSideNumberString".toFloat())
//        } else ASTNode.IntLiteral(leftSideNumberString.toInt())
//    }
//
//    private fun transformASTFromValDeclaration(rootNode: ParseTreeNode.Inner): ASTNode.ValDeclaration {
//        check(rootNode.children.size == 6)
//        val name = (rootNode.children[3] as ParseTreeNode.Leaf).token.content
//        val type =
//            if ((rootNode.children[5] as ParseTreeNode.Inner).children.size == 6)
//                ((rootNode.children[5] as ParseTreeNode.Inner).children[2] as ParseTreeNode.Leaf).token.content
//            else null
//        throw CompilationError("")
////        val value = transformAST((rootNode.children[5] as ParseTreeNode.Inner).last())
////        check(value is ASTNode.Expression)
////        return ASTNode.ValDeclaration(name, type, value)
//    }
}