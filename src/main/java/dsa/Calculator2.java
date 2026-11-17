import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

import org.w3c.dom.Node;

/**
 * Advanced calculator.
 *
 * Pipeline:  String -> tokenize() -> parse (recursive descent) -> Node tree -> eval(variables)
 *
 * The parser builds a tree of Nodes instead of computing right away. That is what lets
 * sum(...) / prod(...) re-evaluate their body many times with a different loop variable,
 * and lets variables be looked up at evaluation time.
 *
 * Grammar (lowest to highest precedence):
 *   expression := term (('+' | '-') term)*
 *   term       := unary (('*' | '/' | '//' | '%' | implicit-multiply) unary)*
 *   unary      := ('-' | '+') unary | power
 *   power      := postfix ('^' unary)?            (right associative)
 *   postfix    := atom '!'*
 *   atom       := NUMBER | VARIABLE | '(' expression ')' | ROOT atom | FUNC '(' args ')'
 *
 * Compile with: javac -encoding UTF-8 Calculator2.java
 */
public class Calculator2 {

    // ------------------------------------------------------------------ tokens

    private enum Types {
        NUMBER, VAR, FUNC, ROOT,
        PLUS, MINUS, MUL, DIV, IDIV, MODULO, POW, FACT,
        LPAREN, RPAREN, COMMA, EOF
    }

    private static class Token {
        final Types type;
        final double value;
        final String text;

        Token(Types type, String text) {
            this.type = type;
            this.text = text;
            this.value = 0;
        }

        Token(double value) {
            this.type = Types.NUMBER;
            this.value = value;
            this.text = String.valueOf(value);
        }

        @Override
        public String toString() {
            return type + "(" + text + ")";
        }
    }

    // ------------------------------------------------------------------ AST

    private interface Node {
        double eval(Map<String, Double> vars);
    }

    /** Kept as its own class so a!/b! can be detected and computed without overflowing. */
    private static class FactNode implements Node {
        final Node arg;

        FactNode(Node arg) {
            this.arg = arg;
        }

        @Override
        public double eval(Map<String, Double> vars) {
            return factorial(toLong(arg.eval(vars), "Factorial argument"));
        }
    }

    // ------------------------------------------------------------------ state

    private static final Set<String> FUNCTIONS = Set.of(
            "sqrt", "cbrt", "log", "log2", "ln", "floor", "ceil", "abs",
            "sum", "prod", "dsum", "dprod");

    private static final long MAX_ITERATIONS = 5_000_000L;

    private List<Token> tokens;
    private int pos;
    private final Map<String, Double> variables = new HashMap<>();

    public Calculator2() {
    }

    // ------------------------------------------------------------------ public API

    /**
     * Evaluates an expression, or performs an assignment such as "A = 5" or "B = A * 2".
     * Assignments return the assigned value.
     */
    public double evaluate(String input) {
        String text = input == null ? "" : input.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Empty expression");
        }

        String target = null;
        int eq = text.indexOf('=');
        if (eq >= 0) {
            String lhs = text.substring(0, eq).trim();
            if (!lhs.matches("[A-Za-z]")) {
                throw new IllegalArgumentException("Invalid assignment target: '" + lhs + "' (use a single letter)");
            }
            target = lhs;
            text = text.substring(eq + 1);
        }

        tokens = tokenize(text);
        pos = 0;
        Node root = parseExpression();
        expect(Types.EOF);

        double result = root.eval(variables);
        if (Double.isNaN(result) || Double.isInfinite(result)) {
            throw new ArithmeticException("Result is undefined or too large");
        }
        if (target != null) {
            variables.put(target, result);
        }
        return result;
    }

    public void setVariable(String name, double value) {
        if (name == null || !name.matches("[A-Za-z]")) {
            throw new IllegalArgumentException("Variable names must be a single letter");
        }
        variables.put(name, value);
    }

    public Double getVariable(String name) {
        return variables.get(name);
    }

    public Map<String, Double> getVariables() {
        return Collections.unmodifiableMap(variables);
    }

    public void clearVariables() {
        variables.clear();
    }

    // ------------------------------------------------------------------ tokenizer

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private List<Token> tokenize(String s) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int n = s.length();

        while (i < n) {
            char c = s.charAt(i);

            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }

            // numbers
            if (Character.isDigit(c) || c == '.') {
                int start = i;
                while (i < n && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                    i++;
                }
                String num = s.substring(start, i);
                try {
                    out.add(new Token(Double.parseDouble(num)));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid number: " + num);
                }
                continue;
            }

            // words: a known function name, otherwise a run of single-letter variables (xy -> x*y)
            if (isAsciiLetter(c)) {
                int start = i;
                while (i < n && isAsciiLetter(s.charAt(i))) {
                    i++;
                }
                String word = s.substring(start, i);
                if (word.equals("log") && i < n && s.charAt(i) == '2') {
                    word = "log2";
                    i++;
                }
                if (FUNCTIONS.contains(word)) {
                    out.add(new Token(Types.FUNC, word));
                } else {
                    for (char ch : word.toCharArray()) {
                        out.add(new Token(Types.VAR, String.valueOf(ch)));
                    }
                }
                continue;
            }

            // symbols
            i++;
            switch (c) {
                case '+' -> out.add(new Token(Types.PLUS, "+"));
                case '-', '\u2212' -> out.add(new Token(Types.MINUS, "-"));          // - and the unicode minus
                case '\u00D7', '\u00B7' -> out.add(new Token(Types.MUL, "*"));       // × and ·
                case '\u00F7' -> out.add(new Token(Types.DIV, "/"));                 // ÷
                case '*' -> {
                    if (i < n && s.charAt(i) == '*') {                               // ** is also exponent
                        i++;
                        out.add(new Token(Types.POW, "^"));
                    } else {
                        out.add(new Token(Types.MUL, "*"));
                    }
                }
                case '/' -> {
                    if (i < n && s.charAt(i) == '/') {                               // // is integer division
                        i++;
                        out.add(new Token(Types.IDIV, "//"));
                    } else {
                        out.add(new Token(Types.DIV, "/"));
                    }
                }
                case '%' -> out.add(new Token(Types.MODULO, "%"));
                case '^' -> out.add(new Token(Types.POW, "^"));
                case '!' -> out.add(new Token(Types.FACT, "!"));
                case '(' -> out.add(new Token(Types.LPAREN, "("));
                case ')' -> out.add(new Token(Types.RPAREN, ")"));
                case ',' -> out.add(new Token(Types.COMMA, ","));
                case '\u221A' -> out.add(new Token(Types.ROOT, "sqrt"));             // √
                case '\u221B' -> out.add(new Token(Types.ROOT, "cbrt"));             // ∛
                case '\u03A3' -> {                                                   // Σ or ΣΣ
                    if (i < n && s.charAt(i) == '\u03A3') {
                        i++;
                        out.add(new Token(Types.FUNC, "dsum"));
                    } else {
                        out.add(new Token(Types.FUNC, "sum"));
                    }
                }
                case '\u03A0' -> {                                                   // Π or ΠΠ
                    if (i < n && s.charAt(i) == '\u03A0') {
                        i++;
                        out.add(new Token(Types.FUNC, "dprod"));
                    } else {
                        out.add(new Token(Types.FUNC, "prod"));
                    }
                }
                default -> throw new IllegalArgumentException("Unexpected character: " + c);
            }
        }

        out.add(new Token(Types.EOF, ""));
        return out;
    }

    // ------------------------------------------------------------------ parser helpers

    private Token peek() {
        return tokens.get(pos);
    }

    private Token advance() {
        return tokens.get(pos++);
    }

    private boolean check(Types type) {
        return peek().type == type;
    }

    private Token expect(Types type) {
        if (!check(type)) {
            throw new IllegalArgumentException("Expected " + type + " but got " + peek().type);
        }
        return advance();
    }

    /** True when the next token can start a factor, meaning "2x", "xy", "x(y+1)", "(a)(b)" multiply implicitly. */
    private boolean startsImplicitFactor() {
        Types t = peek().type;
        if (t == Types.VAR || t == Types.FUNC || t == Types.LPAREN || t == Types.ROOT) {
            return true;
        }
        return t == Types.NUMBER && pos > 0 && tokens.get(pos - 1).type == Types.RPAREN;
    }

    // ------------------------------------------------------------------ parser

    private Node parseExpression() {
        Node left = parseTerm();
        while (check(Types.PLUS) || check(Types.MINUS)) {
            final boolean plus = advance().type == Types.PLUS;
            final Node l = left;
            final Node r = parseTerm();
            if (plus) {
                left = v -> l.eval(v) + r.eval(v);
            } else {
                left = v -> l.eval(v) - r.eval(v);
            }
        }
        return left;
    }

    private Node parseTerm() {
        Node left = parseUnary();
        while (true) {
            final Node l = left;
            if (check(Types.MUL)) {
                advance();
                final Node r = parseUnary();
                left = v -> l.eval(v) * r.eval(v);
            } else if (check(Types.DIV)) {
                advance();
                final Node r = parseUnary();
                if (l instanceof FactNode && r instanceof FactNode) {
                    // a! / b!  -> multiply only the numbers between a and b (no overflow, exact)
                    final FactNode fa = (FactNode) l;
                    final FactNode fb = (FactNode) r;
                    left = v -> factorialRatio(
                            toLong(fa.arg.eval(v), "Factorial argument"),
                            toLong(fb.arg.eval(v), "Factorial argument"));
                } else {
                    left = v -> divide(l.eval(v), r.eval(v));
                }
            } else if (check(Types.IDIV)) {
                advance();
                final Node r = parseUnary();
                left = v -> Math.floor(divide(l.eval(v), r.eval(v)));
            } else if (check(Types.MODULO)) {
                advance();
                final Node r = parseUnary();
                left = v -> modulo(l.eval(v), r.eval(v));
            } else if (startsImplicitFactor()) {
                final Node r = parseUnary();
                left = v -> l.eval(v) * r.eval(v);
            } else {
                break;
            }
        }
        return left;
    }

    private Node parseUnary() {
        if (check(Types.MINUS)) {
            advance();
            final Node operand = parseUnary();
            return v -> -operand.eval(v);
        }
        if (check(Types.PLUS)) {
            advance();
            return parseUnary();
        }
        return parsePower();
    }

    private Node parsePower() {
        final Node base = parsePostfix();
        if (check(Types.POW)) {
            advance();
            final Node exponent = parseUnary();   // parseUnary -> parsePower makes ^ right-associative
            return v -> Math.pow(base.eval(v), exponent.eval(v));
        }
        return base;
    }

    private Node parsePostfix() {
        Node node = parseAtom();
        while (check(Types.FACT)) {
            advance();
            node = new FactNode(node);
        }
        return node;
    }

    private Node parseAtom() {
        Token t = peek();

        if (t.type == Types.NUMBER) {
            advance();
            final double value = t.value;
            return v -> value;
        }

        if (t.type == Types.VAR) {
            advance();
            final String name = t.text;
            return v -> {
                Double x = v.get(name);
                if (x == null) {
                    throw new IllegalArgumentException("Undefined variable: " + name);
                }
                return x;
            };
        }

        if (t.type == Types.LPAREN) {
            advance();
            Node inner = parseExpression();
            expect(Types.RPAREN);
            return inner;
        }

        if (t.type == Types.ROOT) {            // √x and ∛x
            advance();
            return applyFunction(t.text, parseAtom());
        }

        if (t.type == Types.FUNC) {
            return parseFunction();
        }

        throw new IllegalArgumentException(
                "Unexpected " + (t.type == Types.EOF ? "end of expression" : "'" + t.text + "'"));
    }

    private Node parseFunction() {
        String name = advance().text;
        expect(Types.LPAREN);

        Node result;
        switch (name) {
            case "sum", "prod", "dsum", "dprod" ->
                    result = parseSeries(name.endsWith("sum"), name.startsWith("d") ? 2 : 1);
            default -> result = applyFunction(name, parseExpression());
        }

        expect(Types.RPAREN);
        return result;
    }

    /**
     * Summation / product.
     *   sum(i, 1, 5, i^2)                    single:  sum of i^2 for i = 1..5
     *   dsum(i, 1, 3, j, 1, 3, i*j)          double:  outer variable first, then inner
     * The inner bounds may use the outer variable, e.g. dsum(i,1,4, j,1,i, i*j).
     */
    private Node parseSeries(final boolean isSum, int levels) {
        final String[] names = new String[levels];
        final Node[] lo = new Node[levels];
        final Node[] hi = new Node[levels];

        for (int k = 0; k < levels; k++) {
            names[k] = expect(Types.VAR).text;
            expect(Types.COMMA);
            lo[k] = parseExpression();
            expect(Types.COMMA);
            hi[k] = parseExpression();
            expect(Types.COMMA);
        }
        final Node body = parseExpression();

        return v -> series(isSum, names, lo, hi, 0, body,
                new HashMap<>(v), new long[]{MAX_ITERATIONS});
    }

    private static double series(boolean isSum, String[] names, Node[] lo, Node[] hi,
                                 int level, Node body, Map<String, Double> scope, long[] budget) {
        if (level == names.length) {
            if (--budget[0] < 0) {
                throw new ArithmeticException("Summation/product has too many iterations");
            }
            return body.eval(scope);
        }

        long from = toLong(lo[level].eval(scope), "Lower bound");
        long to = toLong(hi[level].eval(scope), "Upper bound");

        double acc = isSum ? 0 : 1;
        for (long k = from; k <= to; k++) {
            scope.put(names[level], (double) k);
            double term = series(isSum, names, lo, hi, level + 1, body, scope, budget);
            acc = isSum ? acc + term : acc * term;
        }
        return acc;
    }

    // ------------------------------------------------------------------ functions

    private Node applyFunction(String name, final Node arg) {
        switch (name) {
            case "sqrt":
                return v -> {
                    double x = arg.eval(v);
                    if (x < 0) {
                        throw new ArithmeticException("Square root of a negative number");
                    }
                    return Math.sqrt(x);
                };
            case "cbrt":
                return v -> Math.cbrt(arg.eval(v));
            case "log":
                return v -> {
                    double x = arg.eval(v);
                    if (x <= 0) {
                        throw new ArithmeticException("Logarithm needs a positive number");
                    }
                    return Math.log10(x);
                };
            case "log2":
                return v -> {
                    double x = arg.eval(v);
                    if (x <= 0) {
                        throw new ArithmeticException("Logarithm needs a positive number");
                    }
                    return log2(x);
                };
            case "ln":
                return v -> {
                    double x = arg.eval(v);
                    if (x <= 0) {
                        throw new ArithmeticException("Logarithm needs a positive number");
                    }
                    return Math.log(x);
                };
            case "floor":
                return v -> Math.floor(arg.eval(v));
            case "ceil":
                return v -> Math.ceil(arg.eval(v));
            case "abs":
                return v -> Math.abs(arg.eval(v));
            default:
                throw new IllegalArgumentException("Unknown function: " + name);
        }
    }

    // ------------------------------------------------------------------ math helpers

    private static double divide(double a, double b) {
        if (b == 0) {
            throw new ArithmeticException("Division by zero");
        }
        return a / b;
    }

    private static double modulo(double a, double b) {
        if (b == 0) {
            throw new ArithmeticException("Modulo by zero");
        }
        return a % b;
    }

    private static double log2(double x) {
        double r = Math.log(x) / Math.log(2);
        double rounded = Math.rint(r);
        return Math.abs(r - rounded) < 1e-12 ? rounded : r;   // log2(8) -> exactly 3
    }

    private static long toLong(double x, String what) {
        if (Double.isNaN(x) || Double.isInfinite(x) || x != Math.rint(x)) {
            throw new IllegalArgumentException(what + " must be a whole number, got " + x);
        }
        return (long) x;
    }

    private static double factorial(long n) {
        if (n < 0) {
            throw new ArithmeticException("Factorial is undefined for negative numbers");
        }
        if (n > 170) {
            throw new ArithmeticException("Factorial too large (170! is the maximum)");
        }
        double r = 1;
        for (long k = 2; k <= n; k++) {
            r *= k;
        }
        return r;
    }

    /** a! / b! without computing either factorial in full. */
    private static double factorialRatio(long a, long b) {
        if (a < 0 || b < 0) {
            throw new ArithmeticException("Factorial is undefined for negative numbers");
        }
        if (Math.abs(a - b) > 1_000_000) {
            throw new ArithmeticException("Factorial ratio is too large");
        }
        long lowEnd = Math.min(a, b);
        long highEnd = Math.max(a, b);
        double r = 1;
        for (long k = lowEnd + 1; k <= highEnd; k++) {
            r *= k;
        }
        return a >= b ? r : 1.0 / r;
    }

    // ------------------------------------------------------------------ tiny console runner

    private static String format(double r) {
        if (r == Math.rint(r) && Math.abs(r) < 1e15) {
            return String.valueOf((long) r);
        }
        return String.valueOf(r);
    }

    public static void main(String[] args) {
        Calculator2 calc = new Calculator2();
        // Scanner in = new Scanner(System.in);
        Scanner in = new Scanner(System.in, java.nio.charset.StandardCharsets.UTF_8);
        System.out.println("Advanced calculator. Type an expression, 'A = 5' to store a variable, or 'exit'.");
        while (true) {
            System.out.print("> ");
            if (!in.hasNextLine()) {
                break;
            }
            String line = in.nextLine().trim();
            if (line.equalsIgnoreCase("exit")) {
                break;
            }
            if (line.isEmpty()) {
                continue;
            }
            try {
                System.out.println(format(calc.evaluate(line)));
            } catch (RuntimeException e) {
                System.out.println("Error: " + e.getMessage());
            }
        }
    }
}
