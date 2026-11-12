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

    @Test
    void testRandomFunctionCall() {
        String code = """
                VAR
                    Rnd_Out1 : INT;
                    Rnd_Out2 : INT;
                    Rnd_Out3 : REAL;
                END_VAR

                // Block: Math.Random dropped template
                Rnd_Out1 := Random(
                    Trig := 1,
                    Min := 10,
                    Max := 20
                );

                // Positional bounds
                Rnd_Out2 := Rnd(5, 15);

                // Zero-arg float
                Rnd_Out3 := Random();
                """;

        List<StVariable> vars = List.of(
                new StVariable(StVariable.Scope.VAR, "Rnd_Out1", DataType.INT, "0", ""),
                new StVariable(StVariable.Scope.VAR, "Rnd_Out2", DataType.INT, "0", ""),
                new StVariable(StVariable.Scope.VAR, "Rnd_Out3", DataType.FLOAT, "0.0", "")
        );

        List<StInterpreter.Diagnostic> parserDiags = StInterpreter.validate(code, vars);
        assertTrue(parserDiags.isEmpty(), "Expected no parser diagnostics, got: " + parserDiags);

        List<StLanguageServer.Diagnostic> diags = StLanguageServer.analyze(code, vars, null);
        assertTrue(diags.isEmpty(), "Expected no language server diagnostics, got: " + diags);

        Map<String, Double> localVars = new HashMap<>();
        localVars.put("Rnd_Out1", 0.0);
        localVars.put("Rnd_Out2", 0.0);
        localVars.put("Rnd_Out3", 0.0);

        StInterpreter.execute(code, localVars, null);

        double val1 = localVars.get("Rnd_Out1");
        double val2 = localVars.get("Rnd_Out2");
        double val3 = localVars.get("Rnd_Out3");

        assertTrue(val1 >= 10.0 && val1 <= 20.0, "val1 should be in [10, 20], got: " + val1);
        assertTrue(val2 >= 5.0 && val2 <= 15.0, "val2 should be in [5, 15], got: " + val2);
        assertTrue(val3 >= 0.0 && val3 <= 1.0, "val3 should be in [0.0, 1.0], got: " + val3);
    }

    @Test
    void testCounterAndSinglePulseInSt() {
        String code = """
                VAR
                    Clk : BOOL;
                    Pulse_Out : BOOL;
                    Inst_Counter : INT;
                    Cntr_Q : BOOL;
                    Cntr_CV : INT;
                END_VAR

                Pulse_Out := SinglePulse(Clk);

                Inst_Counter(
                    CU := Pulse_Out,
                    CD := 0,
                    R := 0,
                    PV := 2,
                    Q => Cntr_Q,
                    CV => Cntr_CV
                );
                """;

        List<StVariable> vars = List.of(
                new StVariable(StVariable.Scope.VAR, "Clk", DataType.BOOL, "0", ""),
                new StVariable(StVariable.Scope.VAR, "Pulse_Out", DataType.BOOL, "0", ""),
                new StVariable(StVariable.Scope.VAR, "Inst_Counter", DataType.INT, "0", ""),
                new StVariable(StVariable.Scope.VAR, "Cntr_Q", DataType.BOOL, "0", ""),
                new StVariable(StVariable.Scope.VAR, "Cntr_CV", DataType.INT, "0", "")
        );

        List<StInterpreter.Diagnostic> parserDiags = StInterpreter.validate(code, vars);
        assertTrue(parserDiags.isEmpty(), "Expected no parser diagnostics, got: " + parserDiags);

        List<StLanguageServer.Diagnostic> diags = StLanguageServer.analyze(code, vars, null);
        assertTrue(diags.isEmpty(), "Expected no language server diagnostics, got: " + diags);

        Map<String, Double> localVars = new HashMap<>();
        localVars.put("Clk", 1.0); // 1st rising edge
        localVars.put("Pulse_Out", 0.0);
        localVars.put("Inst_Counter", 0.0);
        localVars.put("Cntr_Q", 0.0);
        localVars.put("Cntr_CV", 0.0);

        StInterpreter.execute(code, localVars, null);
        assertEquals(1.0, localVars.get("Pulse_Out"), "Pulse_Out should be 1 on rising edge of Clk");
        assertEquals(1.0, localVars.get("Cntr_CV"), "Counter should increment to 1");
        assertEquals(0.0, localVars.get("Cntr_Q"), "Q should be 0 because CV (1) < PV (2)");

        // Scan 2: Clk stays 1 -> Pulse_Out should return to 0, Counter does not increment
        StInterpreter.execute(code, localVars, null);
        assertEquals(0.0, localVars.get("Pulse_Out"), "Pulse_Out should be 0 when Clk remains 1");
        assertEquals(1.0, localVars.get("Cntr_CV"), "Counter should stay at 1");

        // Scan 3: Clk goes 0
        localVars.put("Clk", 0.0);
        StInterpreter.execute(code, localVars, null);
        assertEquals(0.0, localVars.get("Pulse_Out"));
        assertEquals(1.0, localVars.get("Cntr_CV"));

        // Scan 4: Clk goes 1 -> 2nd rising edge -> pulse is 1, Counter increments to 2, Q turns 1
        localVars.put("Clk", 1.0);
        StInterpreter.execute(code, localVars, null);
        assertEquals(1.0, localVars.get("Pulse_Out"));
        assertEquals(2.0, localVars.get("Cntr_CV"), "Counter should increment to 2");
        assertEquals(1.0, localVars.get("Cntr_Q"), "Q should be 1 because CV (2) >= PV (2)");
    }
}
