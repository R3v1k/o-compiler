package o

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SemanticAnalyzerTest {
    private fun parse(source: String): Program = Parser(Lexer(source.trimIndent()).tokenize()).parseProgram()
    private fun analyze(source: String): SemanticResult = SemanticAnalyzer().analyze(parse(source))
    private fun valid(source: String): SemanticResult = analyze(source).also {
        assertTrue(it.isSuccess, it.diagnostics.joinToString("\n"))
    }
    private fun invalid(source: String, message: String): SemanticResult = analyze(source).also {
        assertFalse(it.isSuccess, "Expected a diagnostic containing '$message'")
        assertTrue(it.diagnostics.any { diagnostic -> message in diagnostic.message }, it.diagnostics.joinToString("\n"))
    }
    private fun body(source: String): String = "class Main is this is $source end end"

    @Test fun emptyProgramIsValid() { valid("") }
    @Test fun emptyClassHasImplicitDefaultConstructor() { valid(body("var x : Other()") + " class Other is end") }

    @Test fun literalAndCallTypesAreRecordedOutsideTheAst() {
        val program = parse("class A is var x : 1.Plus(2.5) var b : true.Not end")
        val result = SemanticAnalyzer().analyze(program).requireSuccess()
        val initializer = (program.classes.single().members[0] as VarDecl).init
        assertEquals(OType.REAL, result.model.typeOf(initializer))
        assertEquals(OType.BOOLEAN, result.model.typeOf((program.classes.single().members[1] as VarDecl).init))
    }

    @Test fun undeclaredVariableHasExactSourcePosition() {
        val result = invalid("""
            class A is
                method f : Integer => missing
            end
        """, "Undefined variable 'missing'")
        val diagnostic = result.diagnostics.single()
        assertEquals(2 to 27, diagnostic.line to diagnostic.column)
        assertTrue(diagnostic.toString().startsWith("Semantic error at 2:27:"))
    }

    @Test fun assignmentToUndeclaredVariableIsRejected() { invalid(body("missing := 1"), "Undefined variable 'missing'") }
    @Test fun localsRequireDeclarationBeforeUse() { invalid(body("var a : b var b : 1"), "Undefined variable 'b'") }
    @Test fun localCannotInitializeItself() { invalid(body("var a : a"), "Undefined variable 'a'") }
    @Test fun priorLocalCanInitializeLaterLocal() { valid(body("var a : 1 var b : a b := a.Plus(1)")) }

    @Test fun nestedBlocksPermitShadowingAndUseTheOuterInitializer() {
        valid(body("var x : 1 if true then var x : x x := 2 end x := 3"))
    }

    @Test fun shadowedTypesDoNotLeak() {
        valid(body("var x : 1 if true then var x : true x := false end x := 2"))
    }

    @Test fun ifLocalDoesNotEscape() { invalid(body("if true then var x : 1 end var y : x"), "Undefined variable 'x'") }
    @Test fun elseDoesNotSeeThenDeclarations() { invalid(body("if true then var x : 1 else var y : x end"), "Undefined variable 'x'") }
    @Test fun whileLocalDoesNotEscape() { invalid(body("while false loop var x : 1 end var y : x"), "Undefined variable 'x'") }
    @Test fun whileCanAssignEnclosingVariable() { valid(body("var x : 0 while x.Less(3) loop x := x.Plus(1) end")) }
    @Test fun duplicateLocalRejected() { invalid(body("var x : 1 var x : 2"), "Duplicate variable declaration 'x'") }
    @Test fun duplicateFieldsRejected() { invalid("class A is var x : 1 var x : true end", "Duplicate variable declaration 'x'") }
    @Test fun duplicateClassesRejected() { invalid("class A is end class A is end", "Duplicate class declaration 'A'") }
    @Test fun predefinedClassCannotBeRedefined() { invalid("class Integer is end", "predefined classes cannot be redefined") }
    @Test fun duplicateMethodParametersRejected() { invalid("class A is method f(x: Integer, x: Real) is end end", "Duplicate parameter 'x'") }
    @Test fun duplicateConstructorParametersRejected() { invalid("class A is this(x: Integer, x: Real) is end end", "Duplicate parameter 'x'") }
    @Test fun parameterAndLocalShareTheMethodScope() { invalid("class A is method f(x: Integer) is var x : 1 end end", "Duplicate variable declaration 'x'") }
    @Test fun parameterMayShadowAField() { valid("class A is var x : true method f(x: Integer) : Integer => x end") }
    @Test fun methodsSeeFieldsDeclaredLater() { valid("class A is method f : Integer => x var x : 1 end") }
    @Test fun fieldInitializersSeeEarlierFields() { valid("class A is var a : 1 var b : a.Plus(1) end") }
    @Test fun fieldInitializersRejectLaterFields() { invalid("class A is var a : b var b : 1 end", "Field 'b' is used before its initializer") }
    @Test fun fieldInitializersRejectThisSelfReference() { invalid("class A is var a : this.a end", "Field 'a' is used before its initializer") }

    @Test fun fieldsReadThroughOtherObjects() {
        valid("class A is var x : 1 end class B is method f(a: A) : Integer => a.x end")
    }

    @Test fun inheritedFieldsAndMethodsAreVisibleTransitively() {
        valid("""
            class C extends B is method f : Integer is x := 3 return get() end end
            class B extends A is end
            class A is var x : 1 method get : Integer => x end
        """)
    }

    @Test fun unknownBaseClassRejected() { invalid("class A extends Missing is end", "Unknown base class 'Missing'") }
    @Test fun directInheritanceCycleRejected() { invalid("class A extends A is end", "Inheritance cycle") }
    @Test fun indirectInheritanceCycleRejectedWithoutRecursionFailure() {
        invalid("class A extends B is method f : Integer => 1 end class B extends C is end class C extends A is end", "Inheritance cycle")
    }
    @Test fun genericBaseWithoutArgumentsIsRejected() { invalid("class A extends Array is end", "generic inheritance is unsupported") }
    @Test fun nominalPrimitiveInheritanceIsNotInventedAsIllegal() { valid("class A extends Integer is method f : Integer => this.Plus(1) end") }
    @Test fun unknownParameterTypeRejected() { invalid("class A is method f(x: Missing) is end end", "Unknown type 'Missing'") }
    @Test fun unknownReturnTypeRejected() { invalid("class A is method f : Missing => 1 end", "Unknown type 'Missing'") }
    @Test fun unknownConstructorTypeRejected() { invalid("class A is this(x: Missing) is end end", "Unknown type 'Missing'") }
    @Test fun unknownConstructionRejected() { invalid(body("var x : Missing()"), "Undefined method or class 'Missing'") }
    @Test fun bareClassNameIsNotAConstruction() { invalid(body("var x : Integer"), "cannot be used as a value") }
    @Test fun staticNumericConstantsAreAvailable() { valid(body("var x : Integer.Min var y : Real.Epsilon")) }
    @Test fun instanceNumericConstantsAreAvailable() { valid(body("var x : 1.Max var y : 1.0.Min")) }
    @Test fun predefinedConstantIsNotCallable() { invalid(body("var x : Integer.Min()"), "Field 'Min' is not callable") }
    @Test fun classReceiverCannotInvokeInstanceMethods() { invalid(body("var x : Integer.Plus(1)"), "cannot be used as a value") }
    @Test fun localShadowsClassNameForMemberResolution() {
        invalid("class A is method f(Integer: Boolean) : Integer => Integer.Min end", "Undefined method 'Min' on type Boolean")
    }
    @Test fun zeroParameterMethodShadowsClassNameForMemberResolution() {
        invalid("class A is method Integer : Boolean => true method f : Integer => Integer.Min end", "Undefined method 'Min' on type Boolean")
    }
    @Test fun classNameShadowingKeepsTheActualReceiverType() {
        valid("class A is method Integer : Real => 1.0 method f : Real => Integer.Min end")
    }

    @Test fun exactNumericAssignmentsAreRequired() { invalid(body("var x : 1 x := 1.5"), "Cannot assign Real") }
    @Test fun incompatibleAssignmentRejected() { invalid(body("var x : 1 x := true"), "Cannot assign Boolean") }
    @Test fun explicitNumericConversionPermitted() { valid(body("var x : 1 x := 1.5.toInteger")) }
    @Test fun baseVariableAcceptsDerivedInstance() {
        valid("class Base is end class Derived extends Base is end " + body("var x : Base() x := Derived()"))
    }
    @Test fun derivedVariableRejectsBaseInstance() {
        invalid("class Base is end class Derived extends Base is end " + body("var x : Derived() x := Base()"), "Cannot assign Base")
    }

    @Test fun undefinedMemberMethodRejected() { invalid(body("1.missing()"), "Undefined method 'missing'") }
    @Test fun correctUserMethodCallAccepted() { valid("class A is method f(x: Integer) : Integer => x this is var a : this.f(1) end end") }
    @Test fun unqualifiedMethodCallAccepted() { valid("class A is method f(x: Integer) : Integer => x method g : Integer => f(1) end") }
    @Test fun bareZeroParameterMethodCallAccepted() { valid("class A is method f : Integer => 1 method g : Integer => f end") }
    @Test fun wrongArgumentCountRejected() { invalid("class A is method f(x: Integer) is end this is this.f() end end", "has no matching overload for arguments ()") }
    @Test fun wrongArgumentTypeRejected() { invalid(body("1.Plus(true)"), "has no matching overload for arguments (Boolean)") }
    @Test fun variablesAreNotCallable() { invalid(body("var x : 1 x()"), "Variable 'x' is not callable") }
    @Test fun zeroArgumentMemberWithoutParenthesesCannotSkipRequiredArgument() { invalid(body("var x : 1.Plus"), "has no matching overload for arguments ()") }
    @Test fun overloadsAreResolvedBySignature() {
        valid("class A is method f(x: Integer) : Integer => x method f(x: Boolean) : Boolean => x this is var n : f(1) var b : f(true) end end")
    }
    @Test fun overloadSelectionPrefersMostSpecificSubtype() {
        valid("""
            class Base is end class Derived extends Base is end
            class A is
                method f(x: Base) : Boolean => true
                method f(x: Derived) : Integer => 1
                method g : Integer => f(Derived())
            end
        """)
    }
    @Test fun incomparableApplicableOverloadsAreAmbiguous() {
        invalid("""
            class Base is end class Derived extends Base is end
            class A is
                method f(x: Base, y: Derived) is end
                method f(x: Derived, y: Base) is end
                this is f(Derived(), Derived()) end
            end
        """, "is ambiguous")
    }
    @Test fun duplicateSignatureCannotDifferOnlyByReturnType() {
        invalid("class A is method f(x: Integer) : Integer => x method f(y: Integer) : Boolean => true end", "Duplicate method signature")
    }
    @Test fun inheritedOverloadsRemainVisibleBesideOverride() {
        valid("class A is method f(x: Integer) : Integer => x method f(x: Boolean) : Boolean => x end " +
            "class B extends A is method f(x: Integer) : Integer => x.Plus(1) method g : Boolean => f(true) end")
    }
    @Test fun covariantOverrideAccepted() {
        valid("class A is method f : A => this end class B extends A is method f : B => this end")
    }
    @Test fun incompatibleOverrideRejected() {
        invalid("class A is method f : Integer => 1 end class B extends A is method f : Boolean => true end", "Override 'f()' returns Boolean")
    }
    @Test fun matchingForwardCompletionAndMutualRecursionAccepted() {
        valid("class A is method first(x: Integer) : Integer method second(x: Integer) : Integer => first(x) method first(n: Integer) : Integer => second(n) end")
    }
    @Test fun methodsMayReferenceLaterMethodsWithoutForwardHeader() {
        valid("class A is method f : Integer => this.g() method g : Integer => 1 end")
    }
    @Test fun unresolvedForwardRejected() { invalid("class A is method f : Integer end", "Unresolved forward declaration 'f()'") }
    @Test fun repeatedForwardRejected() { invalid("class A is method f method f method f is end end", "Duplicate forward declaration") }
    @Test fun forwardReturnTypeMustMatchCompletion() {
        invalid("class A is method f : Integer method f : Boolean => true end", "Conflicting return types")
    }
    @Test fun constructorArgumentCountsAndTypesAreChecked() { invalid("class A is this(x: Integer) is end this is var a : A(true) end end", "Constructor 'A' has no matching overload") }
    @Test fun explicitConstructorsSuppressImplicitDefault() {
        invalid("class A is this(x: Integer) is end end " + body("var x : A()"), "Constructor 'A' has no matching overload")
    }
    @Test fun constructorOverloadsAccepted() {
        valid("class A is this(x: Integer) is end this(x: Boolean) is end end " + body("var a : A(1) var b : A(true)"))
    }
    @Test fun duplicateConstructorsRejected() { invalid("class A is this(x: Integer) is end this(y: Integer) is end end", "Duplicate constructor signature") }

    @Test fun nonBooleanIfRejected() { invalid(body("if 1 then end"), "Expected Boolean condition, found Integer") }
    @Test fun nonBooleanWhileRejected() { invalid(body("while 1.0 loop end"), "Expected Boolean condition, found Real") }
    @Test fun booleanConditionsAndOperatorsAccepted() { valid(body("if true.And(false.Not()) then end while false.Or(true).Not loop end")) }
    @Test fun booleanSubtypesAreAcceptedInConditions() { valid("class Flag extends Boolean is end " + body("if Flag() then end while Flag() loop end")) }
    @Test fun returnTypeMismatchRejected() { invalid("class A is method f : Integer is return true end end", "Return type mismatch: expected Integer, found Boolean") }
    @Test fun emptyReturnFromValueMethodRejected() { invalid("class A is method f : Integer is return end end", "Return requires a value of type Integer") }
    @Test fun valueReturnFromVoidMethodRejected() { invalid("class A is method f is return 1 end end", "Method without a return type cannot return a value") }
    @Test fun valueReturnFromConstructorRejected() { invalid(body("return 1"), "Constructor cannot return a value") }
    @Test fun emptyReturnFromConstructorAccepted() { valid(body("return")) }
    @Test fun emptyReturnFromVoidMethodAccepted() { valid("class A is method f is return end end") }
    @Test fun expressionBodyReturnTypeChecked() { invalid("class A is method f : Integer => true end", "Return type mismatch") }
    @Test fun missingReturnPathRejected() { invalid("class A is method f(b: Boolean) : Integer is if b then return 1 end end end", "may complete without returning Integer") }
    @Test fun bothBranchesReturningAccepted() { valid("class A is method f(b: Boolean) : Integer is if b then return 1 else return 2 end end end") }
    @Test fun literalConditionalReturnPathAccepted() { valid("class A is method f : Integer is if true then return 1 end end end") }
    @Test fun loopsDoNotProveARequiredReturn() { invalid("class A is method f : Integer is while true loop return 1 end end end", "may complete without returning Integer") }
    @Test fun codeFollowingReturnIsStillSemanticallyChecked() { invalid("class A is method f : Integer is return 1 var x : missing end end", "Undefined variable 'missing'") }
    @Test fun voidCallCannotInitializeVariable() { invalid("class A is method f is end this is var x : f() end end", "Expression must produce a value") }
    @Test fun voidCallCannotBeAMethodArgument() { invalid("class A is method f is end method g(x: Integer) is end this is g(f()) end end", "Expression must produce a value") }
    @Test fun voidCallCannotBeAMemberReceiver() { invalid("class A is method f is end this is f().Plus(1) end end", "without a value") }
    @Test fun voidCallCanBeAnExpressionStatement() { valid("class A is method f is end this is f() end end") }
    @Test fun arbitraryPureExpressionStatementsRemainValid() { valid(body("1 true 1.Plus(2)")) }

    @Test fun arrayOperationsSubstituteTheElementType() {
        valid("class A is method f(a: Array[Integer]) : Integer is a.set(1, 2) return a.get(1).Plus(a.Length) end end")
    }
    @Test fun arraySetChecksElementType() { invalid(body("var a : Array[Integer](3) a.set(1, true)"), "has no matching overload for arguments (Integer, Boolean)") }
    @Test fun arrayGetChecksIndexType() { invalid(body("var a : Array[Integer](3) var x : a.get(true)"), "has no matching overload for arguments (Boolean)") }
    @Test fun arrayLengthChecksConstructorArgument() { invalid(body("var a : Array[Integer](true)"), "Constructor 'Array[Integer]' has no matching overload") }
    @Test fun listAndArrayConversionsPreserveNestedElementTypes() {
        valid("class A is method f(a: Array[Array[Real]]) : Real => a.toList.tail().head().get(1) end")
    }
    @Test fun listConstructorsAndMethodsAreTyped() { valid(body("var a : List[Integer]() var b : a.append(1) var c : b.head() c := 2")) }
    @Test fun listRepeatedElementConstructorIsTyped() { valid(body("var a : List[Boolean](true, 3) var x : a.head x := false")) }
    @Test fun listConstructorChecksElementType() { invalid(body("var a : List[Boolean](1)"), "Constructor 'List[Boolean]' has no matching overload") }
    @Test fun collectionsAreInvariant() {
        invalid("class A is end class B extends A is end " + body("var a : List[A]() a := List[B]()"), "Cannot assign List[B]")
    }
    @Test fun collectionTypeArgumentsAreRequired() { invalid("class A is method f(a: Array) is end end", "expects 1 type argument(s), found 0") }
    @Test fun unrelatedTypeArgumentsAreRejected() { invalid("class A is method f(a: Integer[Boolean]) is end end", "expects 0 type argument(s), found 1") }
    @Test fun unknownNestedTypeArgumentRejected() { invalid("class A is method f(a: Array[Missing]) is end end", "Unknown type 'Missing'") }
    @Test fun numericBuiltinsUseOnlySpecifiedOverloads() {
        valid(body("var a : 1.Plus(2.0) var b : 1.0.Rem(2) var c : 1.Less(2.0) var d : true.toInteger()"))
        invalid(body("var a : 1.Rem(2.0)"), "has no matching overload")
    }
    @Test fun primitiveDefaultConstructorsRemainCompatibleWithParserFixture() { valid(body("var a : Integer() var b : Real() var c : Boolean()")) }

    @Test fun analysisDoesNotMutateTheProgramOrItsLists() {
        val program = parse("class A is var x : 1 method f : Integer is var a : x if true then return a else return 0 end end end")
        val printed = AstPrinter.render(program)
        val snapshot = parse("class A is var x : 1 method f : Integer is var a : x if true then return a else return 0 end end end")
        val members = program.classes.single().members
        SemanticAnalyzer().analyze(program).requireSuccess()
        assertEquals(snapshot, program)
        assertEquals(printed, AstPrinter.render(program))
        assertSame(members, program.classes.single().members)
    }
    @Test fun analyzerCanBeReusedWithoutLeakingDeclarationsOrDiagnostics() {
        val analyzer = SemanticAnalyzer()
        assertFalse(analyzer.analyze(parse(body("missing := 1"))).isSuccess)
        assertTrue(analyzer.analyze(parse("class A is end")).isSuccess)
        assertTrue(analyzer.analyze(parse("class A is end")).isSuccess)
    }
    @Test fun requireSuccessThrowsStructuredException() {
        val result = analyze(body("missing := 1"))
        val exception = assertFailsWith<SemanticException> { result.requireSuccess() }
        assertEquals(result.diagnostics, exception.diagnostics)
    }
    @Test fun typeModelExposesTransitiveAssignability() {
        val result = valid("class A is end class B extends A is end class C extends B is end")
        assertTrue(result.model.isAssignable(OType("A"), OType("C")))
        assertTrue(result.model.isAssignable(OType("AnyRef"), OType("C")))
        assertFalse(result.model.isAssignable(OType("C"), OType("A")))
        assertFalse(result.model.isAssignable(OType.REAL, OType.INTEGER))
    }
    @Test fun allWorkingLegacyExamplesRemainSemanticallyValid() {
        val examples = File("examples").listFiles()!!.filter {
            it.extension == "o" && it.name.substringBefore('_').toIntOrNull() in 1..10 &&
                it.name !in setOf("03_tricky.o", "04_error.o")
        }
        assertTrue(examples.isNotEmpty())
        for (example in examples) {
            val result = analyze(example.readText())
            assertTrue(result.isSuccess, "${example.name}: ${result.diagnostics.joinToString()}")
        }
    }
}
