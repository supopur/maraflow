package cz.maramat.sim;

import cz.maramat.model.DataType;
import cz.maramat.model.StVariable;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class StInterpreterTest {

    @Test
    void testPositionalAndCall() {
        String code = """
                VAR
                    And_Out : BOOL;
                END_VAR
                
                
                // Block: Bool.And
                And_Out := And(
                    1, // BOOL: Enable Execution
                    0, // BOOL: Input 1
                    0 // BOOL: Input 2
                );
                """;

        List<StVariable> vars = List.of(new StVariable(StVariable.Scope.VAR, "And_Out", DataType.BOOL, "0", ""));
        List<StInterpreter.Diagnostic> diags = StInterpreter.validate(code, vars);
        assertTrue(diags.isEmpty(), "Expected 0 diagnostics for positional And(...) call, got: " + diags);

        Map<String, Double> localVars = new HashMap<>();
        localVars.put("And_Out", 0.0);
        StInterpreter.execute(code, localVars, null);
        assertEquals(0.0, localVars.get("And_Out"), 1e-6);

        String trueCode = code.replace("0, // BOOL: Input 1", "1, // BOOL: Input 1")
                              .replace("0 // BOOL: Input 2", "1 // BOOL: Input 2");
        StInterpreter.execute(trueCode, localVars, null);
        assertEquals(1.0, localVars.get("And_Out"), 1e-6);
    }

    @Test
    void testNamedArgumentsAndCall() {
        String code = """
                VAR
                    And_Out : BOOL;
                END_VAR
                
                And_Out := And(
                    En := 1,
                    X1 := 1,
                    X2 := 1
                );
                """;
        List<StVariable> vars = List.of(new StVariable(StVariable.Scope.VAR, "And_Out", DataType.BOOL, "0", ""));
        List<StInterpreter.Diagnostic> diags = StInterpreter.validate(code, vars);
        assertTrue(diags.isEmpty(), "Expected 0 diagnostics for named argument And(...) call, got: " + diags);

        Map<String, Double> localVars = new HashMap<>();
        localVars.put("And_Out", 0.0);
        StInterpreter.execute(code, localVars, null);
        assertEquals(1.0, localVars.get("And_Out"), 1e-6);
    }

    @Test
    void testDiagnosticLineNumberAccuracy() {
        String code = """
                VAR
                    And_Out : BOOL;
                END_VAR
                
                
                
                // Line 7
                And_Out := @invalid; // Line 8
                """;
        List<StVariable> vars = List.of(new StVariable(StVariable.Scope.VAR, "And_Out", DataType.BOOL, "0", ""));
        List<StInterpreter.Diagnostic> diags = StInterpreter.validate(code, vars);
        assertFalse(diags.isEmpty(), "Expected syntax error on line 8");
        assertEquals(8, diags.getFirst().line(), "Diagnostic line must match document line 8 exactly");
    }

    @Test
    void testFunctionBlockCallWithOutputs() {
        String code = """
                VAR
                    Inst_RS : INT;
                    RS_Q : BOOL;
                    RS_nQ : BOOL;
                END_VAR
                
                Inst_RS(
                    En := 1,
                    S := 1,
                    R := 0,
                    Q => RS_Q,
                    nQ => RS_nQ
                );
                """;
        List<StVariable> vars = List.of(
                new StVariable(StVariable.Scope.VAR, "Inst_RS", DataType.INT, "0", ""),
                new StVariable(StVariable.Scope.VAR, "RS_Q", DataType.BOOL, "0", ""),
                new StVariable(StVariable.Scope.VAR, "RS_nQ", DataType.BOOL, "1", "")
        );
        List<StInterpreter.Diagnostic> diags = StInterpreter.validate(code, vars);
        assertTrue(diags.isEmpty(), "Expected 0 diagnostics for FB invocation statement, got: " + diags);
    }

    @Test
    void testLanguageServerNoFalseUndeclaredId() {
        String code = """
                VAR
                    And_Out : BOOL;
                END_VAR
                
                And_Out := And(
                    En := 1,
                    X1 := 0,
                    X2 := 0
                );
                """;
        List<StVariable> vars = List.of(new StVariable(StVariable.Scope.VAR, "And_Out", DataType.BOOL, "0", ""));
        List<StLanguageServer.Diagnostic> diags = StLanguageServer.analyze(code, vars, null);
        assertTrue(diags.isEmpty(), "Expected no warnings/errors from StLanguageServer, got: " + diags);
    }

    @Test
    void testLanguageServerNoFalseInvalidAssignmentOnArrow() {
        String code = """
                VAR
                    Counter : INT;
                    Inst_SR : INT;
                    SR_Q : BOOL;
                    SR_nQ : BOOL;
                END_VAR

                // Block: Bool.SR
                Inst_SR(
                    En := 1,
                    S := 0,
                    R := 0,
                    Q => SR_Q,
                    nQ => SR_nQ
                );
                """;
        List<StVariable> vars = List.of(
                new StVariable(StVariable.Scope.VAR, "Counter", DataType.INT, "0", ""),
                new StVariable(StVariable.Scope.VAR, "Inst_SR", DataType.INT, "0", ""),
                new StVariable(StVariable.Scope.VAR, "SR_Q", DataType.BOOL, "0", ""),
                new StVariable(StVariable.Scope.VAR, "SR_nQ", DataType.BOOL, "0", "")
        );
        List<StLanguageServer.Diagnostic> diags = StLanguageServer.analyze(code, vars, null);
        assertTrue(diags.isEmpty(), "Expected no warnings/errors from StLanguageServer for FB with => output bindings, got: " + diags);
    }
}
