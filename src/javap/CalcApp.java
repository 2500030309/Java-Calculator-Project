package javap;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import javax.swing.*;

public class CalcApp {


    static class FuncMeta {
        int arity;
        DoubleUnaryOperator f1;
        DoubleBinaryOperator f2;

        FuncMeta(int a, DoubleUnaryOperator f) { arity = a; f1 = f; }
        FuncMeta(int a, DoubleBinaryOperator f) { arity = a; f2 = f; }
    }

    static Map<String, FuncMeta> functions = new HashMap<>();

    static void registerFunctions() {
        functions.put("sin", new FuncMeta(1, x -> Math.sin(Math.toRadians(x))));
        functions.put("cos", new FuncMeta(1, x -> Math.cos(Math.toRadians(x))));
        functions.put("tan", new FuncMeta(1, x -> Math.tan(Math.toRadians(x))));
        functions.put("sqrt", new FuncMeta(1, Math::sqrt));
        functions.put("abs", new FuncMeta(1, Math::abs));
        functions.put("log", new FuncMeta(2, (x, base) -> Math.log(x) / Math.log(base))); // log(x, base) corrected
        functions.put("pow", new FuncMeta(2, Math::pow));
        functions.put("max", new FuncMeta(2, Math::max));
    }

    static Map<String, Double> vars = new HashMap<>();
    static List<String> history = new ArrayList<>();
    
    static File errorsFile = new File("errors.log");
    static File historyFile = new File("calc_history.txt");

    enum T {NUM, VAR, OP, FUNC, LP, RP, COMMA}

    static class Token {
        String text;
        T type;
        int prec;
        boolean right;
        boolean unary;

        Token(String s, T t) { text = s; type = t; }
        Token(String s, int p, boolean r, boolean u) {
            text = s;
            type = T.OP;
            prec = p;
            right = r;
            unary = u;
        }
    }

    static List<Token> tokenize(String s) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        boolean expectUnary = true;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (Character.isDigit(c) || c == '.') {
                int j = i;
                while (j < s.length() && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) j++;
                out.add(new Token(s.substring(i, j), T.NUM));
                i = j;
                expectUnary = false;
                continue;
            }
            if (Character.isLetter(c)) {
                int j = i;
                while (j < s.length() && Character.isLetter(s.charAt(j))) j++;
                String name = s.substring(i, j);
                if (functions.containsKey(name)) out.add(new Token(name, T.FUNC));
                else out.add(new Token(name, T.VAR));
                i = j;
                expectUnary = false;
                continue;
            }
            if (c == '(') { out.add(new Token("(", T.LP)); i++; expectUnary = true; continue; }
            if (c == ')') { out.add(new Token(")", T.RP)); i++; expectUnary = false; continue; }
            if (c == ',') { out.add(new Token(",", T.COMMA)); i++; expectUnary = true; continue; }
            if ("+-*/^".indexOf(c) != -1) {
                boolean u = expectUnary;
                out.add(new Token("" + c, prec(c, u), assoc(c, u), u));
                i++;
                expectUnary = true;
                continue;
            }
            throw new RuntimeException("Invalid character " + c);
        }
        return out;
    }

    static int prec(char c, boolean u) {
        if (u) return 5;
        if (c == '^') return 4;
        if (c == '*' || c == '/') return 3;
        if (c == '+' || c == '-') return 2;
        return 0;
    }

    static boolean assoc(char c, boolean u) {
        if (u) return true;
        return (c == '^');
    }

    static List<Token> toRPN(List<Token> in) {
        Stack<Token> st = new Stack<>();
        List<Token> out = new ArrayList<>();

        for (Token t : in) {
            switch (t.type) {
                case NUM, VAR -> out.add(t);
                case FUNC -> st.push(t);
                case OP -> {
                    while (!st.isEmpty() && st.peek().type == T.OP) {
                        Token o2 = st.peek();
                        if ((!t.right && t.prec <= o2.prec) || (t.right && t.prec < o2.prec))
                            out.add(st.pop());
                        else break;
                    }
                    st.push(t);
                }
                case COMMA -> {
                    while (!st.isEmpty() && st.peek().type != T.LP)
                        out.add(st.pop());
                }
                case LP -> st.push(t);
                case RP -> {
                    while (!st.isEmpty() && st.peek().type != T.LP)
                        out.add(st.pop());
                    st.pop();
                    if (!st.isEmpty() && st.peek().type == T.FUNC)
                        out.add(st.pop());
                }
            }
        }
        while (!st.isEmpty()) out.add(st.pop());
        return out;
    }

    static double eval(List<Token> rpn) {
        Stack<Double> st = new Stack<>();

        for (Token t : rpn) {
            switch (t.type) {
                case NUM -> st.push(Double.parseDouble(t.text));
                case VAR -> st.push(vars.getOrDefault(t.text, 0.0));
                case OP -> {
                    if (t.unary) {
                        double x = st.pop();
                        st.push(-x);
                    } else {
                        double b = st.pop(), a = st.pop();
                        switch (t.text) {
                            case "+" -> st.push(a + b);
                            case "-" -> st.push(a - b);
                            case "*" -> st.push(a * b);
                            case "/" -> st.push(a / b);
                            case "^" -> st.push(Math.pow(a, b));
                        }
                    }
                }
                case FUNC -> {
                    FuncMeta f = functions.get(t.text);
                    if (f == null) throw new RuntimeException("Unknown function " + t.text);
                    List<Double> args = new ArrayList<>();
                    for (int i = 0; i < f.arity; i++) args.add(st.pop());
                    Collections.reverse(args);
                    if (f.arity == 1) {
                        st.push(f.f1.applyAsDouble(args.get(0)));
                    } else {
                        // For two-argument functions like log(x, base) or pow(base, exponent)
                        st.push(f.f2.applyAsDouble(args.get(0), args.get(1)));
                    }
                }
            }
        }
        return st.pop();
    }
    
    public static double evaluateExpression(String expr) throws Exception {
        if (expr == null || expr.trim().isEmpty()) {
            return 0.0;
        }
        List<Token> tok = tokenize(expr);
        return eval(toRPN(tok));
    }
    
    static void appendHistory(String s) {
        history.add(s);
        try (FileWriter fw = new FileWriter(historyFile, true)) {
            fw.write(s + "\n");
        } catch (Exception e) {}
    }
    static void logError(Exception e) {
        try (FileWriter fw = new FileWriter(errorsFile, true)) {
            fw.write(new Date().toString() + ": " + e.toString() + "\n");
        } catch (Exception ex) {}
    }


    public static void main(String[] args) {
        registerFunctions();
     
        SwingUtilities.invokeLater(() -> {
            new CalculatorGUI().createAndShowGUI();
        });
    }

    private static class CalculatorGUI implements ActionListener {

        private final JFrame frame = new JFrame("B.Tech Scientific Calculator");
        private final JTextField display = new JTextField();

        private final String[] buttonLabels = {
            "sin", "cos", "tan", "sqrt",
            "(", ")", "C", "/",
            "7", "8", "9", "*",
            "4", "5", "6", "-",
            "1", "2", "3", "+",
            "log", "pow", "0", "=",
        };

        public void createAndShowGUI() {
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setLayout(new BorderLayout());

            display.setEditable(false);
            display.setFont(new Font("Monospaced", Font.BOLD, 30));
            display.setHorizontalAlignment(JTextField.RIGHT);
            display.setPreferredSize(new Dimension(400, 60)); 
            frame.add(display, BorderLayout.NORTH);

            JPanel buttonPanel = new JPanel(new GridLayout(6, 4, 5, 5)); 
            
            for (String label : buttonLabels) {
                JButton button = new JButton(label);
                button.setFont(new Font("Arial", Font.PLAIN, 18));
                
                if ("+-*/=".contains(label)) {
                    button.setBackground(new Color(255, 192, 0)); 
                } else if (label.length() > 1 || "()".contains(label)) {
                    button.setBackground(new Color(200, 220, 255)); 
                } else if (label.equals("C")) {
                    button.setBackground(new Color(255, 100, 100)); 
                }
                
                button.addActionListener(this); 
                buttonPanel.add(button);
            }

            frame.add(buttonPanel, BorderLayout.CENTER);

            frame.pack();
            frame.setVisible(true);
        }

        public void actionPerformed(ActionEvent e) {
            String command = e.getActionCommand();
            String currentText = display.getText();

            if (command.equals("=")) {
                try {
                    String expression = currentText;
                    double result = evaluateExpression(expression);
                    
                    appendHistory(expression + " = " + result); 

                    String formattedResult = String.format("%.8f", result).replaceAll("\\.?0*$", "");
                    display.setText(formattedResult);

                } catch (Exception ex) {
                    logError(ex); 
                    
                    display.setText("Error: " + ex.getMessage());
                }
            } else if (command.equals("C")) {
                display.setText("");
            } else if (Arrays.asList("sin", "cos", "tan", "sqrt", "log", "pow").contains(command)) {
                 display.setText(currentText + command + "(");
            } else {
                display.setText(currentText + command);
            }
        }
    }
}