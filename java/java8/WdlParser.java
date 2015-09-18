
import java.util.*;
import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import org.apache.commons.lang3.StringEscapeUtils;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.lang.reflect.Method;
public class WdlParser {
    private static Map<Integer, List<TerminalIdentifier>> nonterminal_first;
    private static Map<Integer, List<TerminalIdentifier>> nonterminal_follow;
    private static Map<Integer, List<TerminalIdentifier>> rule_first;
    private static Map<Integer, List<String>> nonterminal_rules;
    private static Map<Integer, String> rules;
    public static WdlTerminalMap terminal_map = new WdlTerminalMap(WdlTerminalIdentifier.values());
    public WdlParser() {
        try {
            lexer_init();
        } catch(Exception e) {}
    }
    public static String join(Collection<?> s, String delimiter) {
        StringBuilder builder = new StringBuilder();
        Iterator iter = s.iterator();
        while (iter.hasNext()) {
            builder.append(iter.next());
            if (!iter.hasNext()) {
                break;
            }
            builder.append(delimiter);
        }
        return builder.toString();
    }
    public static String getIndentString(int spaces) {
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < spaces; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }
    public static String readStdin() throws IOException {
        InputStreamReader stream = new InputStreamReader(System.in, "utf-8");
        char buffer[] = new char[System.in.available()];
        try {
            stream.read(buffer, 0, System.in.available());
        } finally {
            stream.close();
        }
        return new String(buffer);
    }
    public static String readFile(String path) throws IOException {
        FileInputStream stream = new FileInputStream(new File(path));
        try {
            FileChannel fc = stream.getChannel();
            MappedByteBuffer bb = fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size());
            /* Instead of using default, pass in a decoder. */
            return Charset.defaultCharset().decode(bb).toString();
        }
        finally {
            stream.close();
        }
    }
    public static class SyntaxError extends Exception {
        public SyntaxError(String message) {
            super(message);
        }
    }
    public interface SyntaxErrorFormatter {
        /* Called when the parser runs out of tokens but isn't finished parsing. */
        String unexpectedEof(String method, List<TerminalIdentifier> expected, List<String> nt_rules);
        /* Called when the parser finished parsing but there are still tokens left in the stream. */
        String excessTokens(String method, Terminal terminal);
        /* Called when the parser is expecting one token and gets another. */
        String unexpectedSymbol(String method, Terminal actual, List<TerminalIdentifier> expected, String rule);
        /* Called when the parser is expecing a tokens but there are no more tokens. */
        String noMoreTokens(String method, TerminalIdentifier expecting, Terminal last);
        /* Invalid terminal is found in the token stream. */
        String invalidTerminal(String method, Terminal invalid);
    }
    public static class TokenStream extends ArrayList<Terminal> {
        private int index;
        public TokenStream(List<Terminal> terminals) {
            super(terminals);
            reset();
        }
        public TokenStream() {
            reset();
        }
        public void reset() {
            this.index = 0;
        }
        public Terminal advance() {
            this.index += 1;
            return this.current();
        }
        public Terminal current() {
            try {
                return this.get(this.index);
            } catch (IndexOutOfBoundsException e) {
                return null;
            }
        }
        public Terminal last() {
          return this.get(this.size() - 1);
        }
    }
    public static class NonTerminal {
        private int id;
        private String string;
        NonTerminal(int id, String string) {
            this.id = id;
            this.string = string;
        }
        public int getId() {
            return this.id;
        }
        public String getString() {
            return this.string;
        }
        public String toString() {
            return this.string;
        }
    }
    public interface AstTransform {}
    public static class AstTransformNodeCreator implements AstTransform {
        private String name;
        private LinkedHashMap<String, Integer> parameters;
        AstTransformNodeCreator(String name, LinkedHashMap<String, Integer> parameters) {
            this.name = name;
            this.parameters = parameters;
        }
        public Map<String, Integer> getParameters() {
            return this.parameters;
        }
        public String getName() {
            return this.name;
        }
        public String toString() {
            LinkedList<String> items = new LinkedList<String>();
            for (final Map.Entry<String, Integer> entry : this.parameters.entrySet()) {
                items.add(entry.getKey() + "=$" + entry.getValue().toString());
            }
            return "AstNodeCreator: " + this.name + "( " + join(items, ", ") + " )";
        }
    }
    public static class AstTransformSubstitution implements AstTransform {
        private int index;
        AstTransformSubstitution(int index) {
            this.index = index;
        }
        public int getIndex() {
            return this.index;
        }
        public String toString() {
            return "AstSubstitution: $" + Integer.toString(this.index);
        }
    }
    public interface AstNode {
        public String toString();
        public String toPrettyString();
        public String toPrettyString(int indent);
    }
    public static class AstList extends ArrayList<AstNode> implements AstNode {
        public String toString() {
            return "[" + join(this, ", ") + "]";
        }
        public String toPrettyString() {
            return toPrettyString(0);
        }
        public String toPrettyString(int indent) {
            String spaces = getIndentString(indent);
            if (this.size() == 0) {
                return spaces + "[]";
            }
            ArrayList<String> elements = new ArrayList<String>();
            for ( AstNode node : this ) {
                elements.add(node.toPrettyString(indent + 2));
            }
            return spaces + "[\n" + join(elements, ",\n") + "\n" + spaces + "]";
        }
    }
    public static class Ast implements AstNode {
        private String name;
        private Map<String, AstNode> attributes;
        Ast(String name, Map<String, AstNode> attributes) {
            this.name = name;
            this.attributes = attributes;
        }
        public AstNode getAttribute(String name) {
            return this.attributes.get(name);
        }
        public Map<String, AstNode> getAttributes() {
            return this.attributes;
        }
        public String getName() {
            return this.name;
        }
        public String toString() {
            Formatter formatter = new Formatter(new StringBuilder(), Locale.US);
            LinkedList<String> attributes = new LinkedList<String>();
            for (final Map.Entry<String, AstNode> attribute : this.attributes.entrySet()) {
                final String name = attribute.getKey();
                final AstNode node = attribute.getValue();
                final String nodeStr = (node == null) ? "None" : node.toString();
                attributes.add(name + "=" + nodeStr);
            }
            formatter.format("(%s: %s)", this.name, join(attributes, ", "));
            return formatter.toString();
        }
        public String toPrettyString() {
            return toPrettyString(0);
        }
        public String toPrettyString(int indent) {
            String spaces = getIndentString(indent);
            ArrayList<String> children = new ArrayList<String>();
            for( Map.Entry<String, AstNode> attribute : this.attributes.entrySet() ) {
                String valueString = attribute.getValue() == null ? "None" : attribute.getValue().toPrettyString(indent + 2).trim();
                children.add(spaces + "  " + attribute.getKey() + "=" + valueString);
            }
            return spaces + "(" + this.name + ":\n" + join(children, ",\n") + "\n" + spaces + ")";
        }
    }
    public interface ParseTreeNode {
        public AstNode toAst();
        public String toString();
        public String toPrettyString();
        public String toPrettyString(int indent);
    }
    public static class Terminal implements AstNode, ParseTreeNode
    {
        private int id;
        private String terminal_str;
        private String source_string;
        private String resource;
        private int line;
        private int col;
        public Terminal(int id, String terminal_str, String source_string, String resource, int line, int col) {
            this.id = id;
            this.terminal_str = terminal_str;
            this.source_string = source_string;
            this.resource = resource;
            this.line = line;
            this.col = col;
        }
        public int getId() {
            return this.id;
        }
        public String getTerminalStr() {
            return this.terminal_str;
        }
        public String getSourceString() {
            return this.source_string;
        }
        public String getResource() {
            return this.resource;
        }
        public int getLine() {
            return this.line;
        }
        public int getColumn() {
            return this.col;
        }
        public String toString() {
            byte[] source_string_bytes;
            try {
                source_string_bytes = this.getSourceString().getBytes("UTF-8");
            } catch (java.io.UnsupportedEncodingException e) {
                source_string_bytes = this.getSourceString().getBytes();
            }
            return String.format("<%s:%d:%d %s \"%s\">",
                this.getResource(),
                this.getLine(),
                this.getColumn(),
                this.getTerminalStr(),
                Base64.getEncoder().encodeToString(source_string_bytes)
            );
        }
        public String toPrettyString() {
            return toPrettyString(0);
        }
        public String toPrettyString(int indent) {
            return getIndentString(indent) + this.toString();
        }
        public AstNode toAst() { return this; }
    }
    public static class ParseTree implements ParseTreeNode {
        private NonTerminal nonterminal;
        private ArrayList<ParseTreeNode> children;
        private boolean isExpr, isNud, isPrefix, isInfix, isExprNud;
        private int nudMorphemeCount;
        private Terminal listSeparator;
        private boolean list;
        private AstTransform astTransform;
        ParseTree(NonTerminal nonterminal) {
            this.nonterminal = nonterminal;
            this.children = new ArrayList<ParseTreeNode>();
            this.astTransform = null;
            this.isExpr = false;
            this.isNud = false;
            this.isPrefix = false;
            this.isInfix = false;
            this.isExprNud = false;
            this.nudMorphemeCount = 0;
            this.listSeparator = null;
            this.list = false;
        }
        public void setExpr(boolean value) { this.isExpr = value; }
        public void setNud(boolean value) { this.isNud = value; }
        public void setPrefix(boolean value) { this.isPrefix = value; }
        public void setInfix(boolean value) { this.isInfix = value; }
        public void setExprNud(boolean value) { this.isExprNud = value; }
        public void setAstTransformation(AstTransform value) { this.astTransform = value; }
        public void setNudMorphemeCount(int value) { this.nudMorphemeCount = value; }
        public void setList(boolean value) { this.list = value; }
        public void setListSeparator(Terminal value) { this.listSeparator = value; }
        public int getNudMorphemeCount() { return this.nudMorphemeCount; }
        public List<ParseTreeNode> getChildren() { return this.children; }
        public boolean isInfix() { return this.isInfix; }
        public boolean isPrefix() { return this.isPrefix; }
        public boolean isExpr() { return this.isExpr; }
        public boolean isNud() { return this.isNud; }
        public boolean isExprNud() { return this.isExprNud; }
        public void add(ParseTreeNode tree) {
            if (this.children == null) {
                this.children = new ArrayList<ParseTreeNode>();
            }
            this.children.add(tree);
        }
        private boolean isCompoundNud() {
            if ( this.children.size() > 0 && this.children.get(0) instanceof ParseTree ) {
                ParseTree child = (ParseTree) this.children.get(0);
                if ( child.isNud() && !child.isPrefix() && !this.isExprNud() && !this.isInfix() ) {
                    return true;
                }
            }
            return false;
        }
        public AstNode toAst() {
            if ( this.list == true ) {
                AstList astList = new AstList();
                int end = this.children.size() - 1;
                if ( this.children.size() == 0 ) {
                    return astList;
                }
                for (int i = 0; i < this.children.size() - 1; i++) {
                    if (this.children.get(i) instanceof Terminal && this.listSeparator != null && ((Terminal)this.children.get(i)).id == this.listSeparator.id)
                        continue;
                    astList.add(this.children.get(i).toAst());
                }
                astList.addAll((AstList) this.children.get(this.children.size() - 1).toAst());
                return astList;
            } else if ( this.isExpr ) {
                if ( this.astTransform instanceof AstTransformSubstitution ) {
                    AstTransformSubstitution astSubstitution = (AstTransformSubstitution) astTransform;
                    return this.children.get(astSubstitution.getIndex()).toAst();
                } else if ( this.astTransform instanceof AstTransformNodeCreator ) {
                    AstTransformNodeCreator astNodeCreator = (AstTransformNodeCreator) this.astTransform;
                    LinkedHashMap<String, AstNode> parameters = new LinkedHashMap<String, AstNode>();
                    ParseTreeNode child;
                    for ( final Map.Entry<String, Integer> parameter : astNodeCreator.getParameters().entrySet() ) {
                        String name = parameter.getKey();
                        int index = parameter.getValue().intValue();
                        if ( index == '$' ) {
                            child = this.children.get(0);
                        } else if ( this.isCompoundNud() ) {
                            ParseTree firstChild = (ParseTree) this.children.get(0);
                            if ( index < firstChild.getNudMorphemeCount() ) {
                                child = firstChild.getChildren().get(index);
                            } else {
                                index = index - firstChild.getNudMorphemeCount() + 1;
                                child = this.children.get(index);
                            }
                        } else if ( this.children.size() == 1 && !(this.children.get(0) instanceof ParseTree) && !(this.children.get(0) instanceof List) ) {
                            // TODO: I don't think this should ever be called
                            child = this.children.get(0);
                        } else {
                            child = this.children.get(index);
                        }
                        parameters.put(name, child.toAst());
                    }
                    return new Ast(astNodeCreator.getName(), parameters);
                }
            } else {
                AstTransformSubstitution defaultAction = new AstTransformSubstitution(0);
                AstTransform action = this.astTransform != null ? this.astTransform : defaultAction;
                if (this.children.size() == 0) return null;
                if (action instanceof AstTransformSubstitution) {
                    AstTransformSubstitution astSubstitution = (AstTransformSubstitution) action;
                    return this.children.get(astSubstitution.getIndex()).toAst();
                } else if (action instanceof AstTransformNodeCreator) {
                    AstTransformNodeCreator astNodeCreator = (AstTransformNodeCreator) action;
                    LinkedHashMap<String, AstNode> evaluatedParameters = new LinkedHashMap<String, AstNode>();
                    for ( Map.Entry<String, Integer> baseParameter : astNodeCreator.getParameters().entrySet() ) {
                        String name = baseParameter.getKey();
                        int index2 = baseParameter.getValue().intValue();
                        evaluatedParameters.put(name, this.children.get(index2).toAst());
                    }
                    return new Ast(astNodeCreator.getName(), evaluatedParameters);
                }
            }
            return null;
        }
        public String toString() {
          ArrayList<String> children = new ArrayList<String>();
          for (ParseTreeNode child : this.children) {
            children.add(child.toString());
          }
          return "(" + this.nonterminal.getString() + ": " + join(children, ", ") + ")";
        }
        public String toPrettyString() {
          return toPrettyString(0);
        }
        public String toPrettyString(int indent) {
          if (this.children.size() == 0) {
            return "(" + this.nonterminal.toString() + ": )";
          }
          String spaces = getIndentString(indent);
          ArrayList<String> children = new ArrayList<String>();
          for ( ParseTreeNode node : this.children ) {
            String sub = node.toPrettyString(indent + 2).trim();
            children.add(spaces + "  " +  sub);
          }
          return spaces + "(" + this.nonterminal.toString() + ":\n" + join(children, ",\n") + "\n" + spaces + ")";
        }
    }
    private static class ParserContext {
        public TokenStream tokens;
        public SyntaxErrorFormatter error_formatter;
        public String nonterminal;
        public String rule;
        public ParserContext(TokenStream tokens, SyntaxErrorFormatter error_formatter) {
            this.tokens = tokens;
            this.error_formatter = error_formatter;
        }
    }
    private static class DefaultSyntaxErrorFormatter implements SyntaxErrorFormatter {
        public String unexpectedEof(String method, List<TerminalIdentifier> expected, List<String> nt_rules) {
            return "Error: unexpected end of file";
        }
        public String excessTokens(String method, Terminal terminal) {
            return "Finished parsing without consuming all tokens.";
        }
        public String unexpectedSymbol(String method, Terminal actual, List<TerminalIdentifier> expected, String rule) {
            ArrayList<String> expected_terminals = new ArrayList<String>();
            for ( TerminalIdentifier e : expected ) {
                expected_terminals.add(e.string());
            }
            return String.format(
                "Unexpected symbol (line %d, col %d) when parsing parse_%s.  Expected %s, got %s.",
                actual.getLine(), actual.getColumn(), method, join(expected_terminals, ", "), actual.toPrettyString()
            );
        }
        public String noMoreTokens(String method, TerminalIdentifier expecting, Terminal last) {
            return "No more tokens.  Expecting " + expecting.string();
        }
        public String invalidTerminal(String method, Terminal invalid) {
            return "Invalid symbol ID: "+invalid.getId()+" ("+invalid.getTerminalStr()+")";
        }
    }
    public interface TerminalMap {
        TerminalIdentifier get(String string);
        TerminalIdentifier get(int id);
        boolean isValid(String string);
        boolean isValid(int id);
    }
    public static class WdlTerminalMap implements TerminalMap {
        private Map<Integer, TerminalIdentifier> id_to_term;
        private Map<String, TerminalIdentifier> str_to_term;
        WdlTerminalMap(WdlTerminalIdentifier[] terminals) {
            id_to_term = new HashMap<Integer, TerminalIdentifier>();
            str_to_term = new HashMap<String, TerminalIdentifier>();
            for( WdlTerminalIdentifier terminal : terminals ) {
                Integer id = new Integer(terminal.id());
                String str = terminal.string();
                id_to_term.put(id, terminal);
                str_to_term.put(str, terminal);
            }
        }
        public TerminalIdentifier get(String string) { return this.str_to_term.get(string); }
        public TerminalIdentifier get(int id) { return this.id_to_term.get(id); }
        public boolean isValid(String string) { return this.str_to_term.containsKey(string); }
        public boolean isValid(int id) { return this.id_to_term.containsKey(id); }
    }
    public interface TerminalIdentifier {
        public int id();
        public String string();
    }
    public enum WdlTerminalIdentifier implements TerminalIdentifier {
        TERMINAL_RAW_CMD_END(0, "raw_cmd_end"),
        TERMINAL_RPAREN(1, "rparen"),
        TERMINAL_TASK(2, "task"),
        TERMINAL_NOT_EQUAL(3, "not_equal"),
        TERMINAL_LTEQ(4, "lteq"),
        TERMINAL_OUTPUT(5, "output"),
        TERMINAL_IDENTIFIER(6, "identifier"),
        TERMINAL_DOUBLE_EQUAL(7, "double_equal"),
        TERMINAL_LT(8, "lt"),
        TERMINAL_CMD_PARAM_END(9, "cmd_param_end"),
        TERMINAL_RAW_COMMAND(10, "raw_command"),
        TERMINAL_PLUS(11, "plus"),
        TERMINAL_IN(12, "in"),
        TERMINAL_STRING(13, "string"),
        TERMINAL_INTEGER(14, "integer"),
        TERMINAL_CMD_ATTR_HINT(15, "cmd_attr_hint"),
        TERMINAL_FQN(16, "fqn"),
        TERMINAL_WORKFLOW(17, "workflow"),
        TERMINAL_NOT(18, "not"),
        TERMINAL_SLASH(19, "slash"),
        TERMINAL_DOUBLE_PIPE(20, "double_pipe"),
        TERMINAL_FLOAT(21, "float"),
        TERMINAL_COMMA(22, "comma"),
        TERMINAL_TYPE_E(23, "type_e"),
        TERMINAL_GT(24, "gt"),
        TERMINAL_OBJECT(25, "object"),
        TERMINAL_RAW_CMD_START(26, "raw_cmd_start"),
        TERMINAL_LBRACE(27, "lbrace"),
        TERMINAL_PERCENT(28, "percent"),
        TERMINAL_RSQUARE(29, "rsquare"),
        TERMINAL_GTEQ(30, "gteq"),
        TERMINAL_META(31, "meta"),
        TERMINAL_SCATTER(32, "scatter"),
        TERMINAL_LPAREN(33, "lparen"),
        TERMINAL_ASTERISK(34, "asterisk"),
        TERMINAL_BOOLEAN(35, "boolean"),
        TERMINAL_QMARK(36, "qmark"),
        TERMINAL_DASH(37, "dash"),
        TERMINAL_TYPE(38, "type"),
        TERMINAL_INPUT(39, "input"),
        TERMINAL_DOUBLE_AMPERSAND(40, "double_ampersand"),
        TERMINAL_CMD_PART(41, "cmd_part"),
        TERMINAL_PARAMETER_META(42, "parameter_meta"),
        TERMINAL_RUNTIME(43, "runtime"),
        TERMINAL_WHILE(44, "while"),
        TERMINAL_E(45, "e"),
        TERMINAL_DOT(46, "dot"),
        TERMINAL_IMPORT(47, "import"),
        TERMINAL_CMD_PARAM_START(48, "cmd_param_start"),
        TERMINAL_LSQUARE(49, "lsquare"),
        TERMINAL_IF(50, "if"),
        TERMINAL_CALL(51, "call"),
        TERMINAL_AS(52, "as"),
        TERMINAL_RBRACE(53, "rbrace"),
        TERMINAL_EQUAL(54, "equal"),
        TERMINAL_COLON(55, "colon"),
        END_SENTINAL(-3, "END_SENTINAL");
        private final int id;
        private final String string;
        WdlTerminalIdentifier(int id, String string) {
            this.id = id;
            this.string = string;
        }
        public int id() {return id;}
        public String string() {return string;}
    }
    /* table[nonterminal][terminal] = rule */
    private static final int[][] table = {
        { -1, -1, -1, -1, -1, -1, 39, -1, -1, -1, -1, 39, -1, 39, 39, 36, -1, -1, 39, -1, -1, 39, -1, -1, -1, 39, -1, 39, -1, -1, -1, -1, -1, 39, -1, 39, -1, 39, -1, -1, -1, -1, -1, -1, -1, 39, -1, -1, -1, 39, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 91, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 49, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, 25, -1, -1, -1, -1, 24, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 28, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 27, 26, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 42, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 42, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 45, -1, -1 },
        { -1, -1, -1, -1, -1, 80, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 80, -1, -1, -1, 79, -1, -1, -1, -1, 80, -1, -1, -1, -1, -1, 80, -1, -1, -1, -1, -1, 80, -1, -1, -1, -1, -1, 80, 80, -1, 80, -1, -1 },
        { -1, -1, -1, -1, -1, 17, -1, -1, -1, -1, 17, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 16, -1, -1, -1, -1, -1, -1, -1, 17, -1, -1, -1, -1, -1, -1, 16, 17, -1, -1, 17, 17, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, 20, -1, -1, -1, -1, 20, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 20, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 20, 20, -1, -1, -1, -1, -1, -1, -1, -1, -1, 21, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 55, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 62, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 14, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 40, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, 92, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 95, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 96, -1, -1 },
        { -1, -1, -1, -1, -1, 67, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 67, -1, -1, -1, -1, -1, -1, -1, -1, 67, -1, -1, -1, -1, -1, 67, -1, -1, -1, -1, -1, 67, -1, -1, -1, -1, -1, 67, 67, -1, 68, -1, -1 },
        { -1, -1, -1, -1, -1, 19, -1, -1, -1, -1, 19, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 19, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 19, 19, -1, -1, -1, -1, -1, -1, -1, -1, -1, 22, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 64, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 63, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 137, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 138, -1, -1 },
        { -1, -1, -1, -1, -1, -1, 52, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 53, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 103, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, 5, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 5, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 107, -1, -1, -1, -1, -1, 110, -1, -1, -1, -1, -1, -1, -1, -1, 107, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, 142, -1, -1, -1, -1, 142, -1, 142, 142, -1, -1, -1, 142, -1, -1, 142, -1, -1, -1, 142, -1, 142, -1, -1, -1, -1, -1, 142, -1, 142, -1, 142, -1, -1, -1, -1, -1, -1, -1, 142, -1, -1, -1, 142, -1, -1, -1, 145, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 50, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, 66, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 66, -1, -1, -1, -1, -1, -1, -1, -1, 66, -1, -1, -1, -1, -1, 66, -1, -1, -1, -1, -1, 66, -1, -1, -1, -1, -1, 66, 66, -1, 69, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 47, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 47, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, 87, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 90, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 90, -1, -1 },
        { -1, -1, -1, -1, -1, -1, 106, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 108, -1, -1, -1, -1, -1, -1, 109, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, 3, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 3, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, 56, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 70, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, 18, -1, -1, -1, -1, 18, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 15, -1, -1, -1, -1, -1, -1, -1, 18, -1, -1, -1, -1, -1, -1, 15, 18, -1, -1, 18, 18, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 33, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 13, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 61, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 61, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, 46, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 100, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 99, -1, -1, -1, -1, -1, -1, 100, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 101, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, 58, -1, -1, -1, -1, 57, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 57, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, 131, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 130, -1, -1, -1, -1, -1, -1, 131, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 81, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 88, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 89, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 89, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 143, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 144, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 43, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 43, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 44, -1, -1 },
        { 32, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 29, -1, -1, -1, -1, -1, -1, 29, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, 38, -1, -1, -1, -1, 38, -1, 38, 38, 37, -1, -1, 38, -1, -1, 38, -1, -1, -1, 38, -1, 38, -1, -1, -1, -1, -1, 38, -1, 38, -1, 38, -1, -1, -1, -1, -1, -1, -1, 38, -1, -1, -1, 38, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 102, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 105, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, 4, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 4, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, 2, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 2, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { 31, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 30, -1, -1, -1, -1, -1, -1, 30, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, 76, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 72, -1, -1, -1, -1, -1, -1, -1, -1, 75, -1, -1, -1, -1, -1, 72, -1, -1, -1, -1, -1, 73, -1, -1, -1, -1, -1, 74, 71, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 83, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 84, -1, -1 },
        { -1, 132, -1, -1, -1, -1, 129, -1, -1, -1, -1, 129, -1, 129, 129, -1, -1, -1, 129, -1, -1, 129, -1, -1, -1, 129, -1, 129, -1, 132, -1, -1, -1, 129, -1, 129, -1, 129, -1, -1, -1, -1, -1, -1, -1, 129, -1, -1, -1, 129, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, 78, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 78, -1, -1, -1, 78, -1, -1, -1, -1, 78, -1, -1, -1, -1, -1, 78, -1, -1, -1, -1, -1, 78, -1, -1, -1, -1, -1, 78, 78, 77, 78, -1, -1 },
        { -1, -1, -1, -1, -1, -1, 51, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 54, -1, -1 },
        { -1, -1, 10, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 9, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 86, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 34, -1, -1, -1, -1, -1, -1, 35, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, 8, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 8, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 8, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 48, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 12, -1, -1, -1, -1, 11, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, 65, -1, -1, -1, -1, 65, -1, 65, 65, -1, -1, -1, 65, -1, -1, 65, -1, -1, -1, 65, -1, 65, -1, -1, -1, -1, -1, 65, -1, 65, -1, 65, -1, -1, -1, -1, -1, -1, -1, 65, -1, -1, -1, 65, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 41, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, 98, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 93, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 104, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 82, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 85, -1, -1 },
        { -1, -1, 23, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, 60, -1, -1, -1, -1, 60, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 60, -1, -1, -1, -1, -1, -1, -1, 60, 60, -1, -1, -1, -1, -1, 60, 60, -1, -1, 60, 60, 60, -1, -1, -1, -1, -1, 60, 60, -1, 60, 59, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 94, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 97, -1, -1 },
        { -1, -1, -1, -1, -1, -1, 136, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 139, -1, -1 },
    };
    static {
        Map<Integer, List<TerminalIdentifier>> map = new HashMap<Integer, List<TerminalIdentifier>>();
        map.put(56, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CMD_ATTR_HINT,
        }));
        map.put(57, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_INPUT,
        }));
        map.put(58, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
        }));
        map.put(59, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_META,
        }));
        map.put(60, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_TYPE,
        }));
        map.put(61, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(62, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_TYPE,
        }));
        map.put(63, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
        }));
        map.put(64, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(65, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_EQUAL,
        }));
        map.put(66, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_AS,
        }));
        map.put(67, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CMD_PARAM_START,
        }));
        map.put(68, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(69, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_COMMA,
        }));
        map.put(70, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WHILE,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_TYPE,
        }));
        map.put(71, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
        }));
        map.put(72, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_QMARK,
            WdlTerminalIdentifier.TERMINAL_PLUS,
        }));
        map.put(73, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_COMMA,
        }));
        map.put(74, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(75, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WHILE,
        }));
        map.put(76, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_TYPE,
        }));
        map.put(77, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
            WdlTerminalIdentifier.TERMINAL_TASK,
        }));
        map.put(78, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_TYPE,
        }));
        map.put(79, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_STRING,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(80, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_META,
        }));
        map.put(81, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WHILE,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_TYPE,
        }));
        map.put(82, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_TYPE,
        }));
        map.put(83, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_STRING,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(84, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(85, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(86, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_COMMA,
        }));
        map.put(87, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IMPORT,
        }));
        map.put(88, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(89, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
        }));
        map.put(90, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_TYPE,
        }));
        map.put(91, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
        }));
        map.put(92, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IMPORT,
        }));
        map.put(93, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_TYPE,
        }));
        map.put(94, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
        }));
        map.put(95, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_DOT,
        }));
        map.put(96, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_FQN,
        }));
        map.put(97, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_QMARK,
            WdlTerminalIdentifier.TERMINAL_PLUS,
        }));
        map.put(98, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_COMMA,
        }));
        map.put(99, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CALL,
        }));
        map.put(100, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_COMMA,
        }));
        map.put(101, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_COMMA,
        }));
        map.put(102, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_TYPE,
        }));
        map.put(103, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CMD_PART,
            WdlTerminalIdentifier.TERMINAL_CMD_PARAM_START,
        }));
        map.put(104, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CMD_ATTR_HINT,
        }));
        map.put(105, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_DOT,
        }));
        map.put(106, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_SCATTER,
        }));
        map.put(107, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
            WdlTerminalIdentifier.TERMINAL_TASK,
        }));
        map.put(108, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IMPORT,
        }));
        map.put(109, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CMD_PART,
            WdlTerminalIdentifier.TERMINAL_CMD_PARAM_START,
        }));
        map.put(110, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WHILE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_CALL,
        }));
        map.put(111, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_INPUT,
        }));
        map.put(112, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_STRING,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(113, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_AS,
        }));
        map.put(114, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(115, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
            WdlTerminalIdentifier.TERMINAL_TASK,
        }));
        map.put(116, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(117, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CMD_PART,
            WdlTerminalIdentifier.TERMINAL_CMD_PARAM_START,
        }));
        map.put(118, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IMPORT,
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
            WdlTerminalIdentifier.TERMINAL_TASK,
        }));
        map.put(119, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
        }));
        map.put(120, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_AS,
        }));
        map.put(121, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_STRING,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(122, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CMD_ATTR_HINT,
        }));
        map.put(123, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
        }));
        map.put(124, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_AS,
        }));
        map.put(125, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IF,
        }));
        map.put(126, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_INPUT,
        }));
        map.put(127, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TASK,
        }));
        map.put(128, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_EQUAL,
        }));
        map.put(129, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_FQN,
        }));
        map.put(130, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        nonterminal_first = Collections.unmodifiableMap(map);
    }
    static {
        Map<Integer, List<TerminalIdentifier>> map = new HashMap<Integer, List<TerminalIdentifier>>();
        map.put(56, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_STRING,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(57, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_INPUT,
        }));
        map.put(58, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
        }));
        map.put(59, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
        }));
        map.put(60, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        map.put(61, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WHILE,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_TYPE,
        }));
        map.put(62, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
            WdlTerminalIdentifier.TERMINAL_INPUT,
        }));
        map.put(63, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        map.put(64, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
        }));
        map.put(65, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_INPUT,
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
            WdlTerminalIdentifier.TERMINAL_WHILE,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        map.put(66, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IMPORT,
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
            WdlTerminalIdentifier.TERMINAL_TASK,
        }));
        map.put(67, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CMD_PART,
            WdlTerminalIdentifier.TERMINAL_CMD_PARAM_START,
            WdlTerminalIdentifier.TERMINAL_RAW_CMD_END,
        }));
        map.put(68, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_COMMA,
            WdlTerminalIdentifier.TERMINAL_INPUT,
        }));
        map.put(69, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        map.put(70, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        map.put(71, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        map.put(72, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(73, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        map.put(74, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        map.put(75, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WHILE,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_TYPE,
        }));
        map.put(76, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_QMARK,
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_RSQUARE,
            WdlTerminalIdentifier.TERMINAL_COMMA,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(77, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(78, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RSQUARE,
        }));
        map.put(79, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        map.put(80, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
        }));
        map.put(81, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        map.put(82, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_TYPE,
        }));
        map.put(83, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RPAREN,
            WdlTerminalIdentifier.TERMINAL_LTEQ,
            WdlTerminalIdentifier.TERMINAL_NOT_EQUAL,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_DOUBLE_EQUAL,
            WdlTerminalIdentifier.TERMINAL_LT,
            WdlTerminalIdentifier.TERMINAL_CMD_PARAM_END,
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_STRING,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_CMD_ATTR_HINT,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_SLASH,
            WdlTerminalIdentifier.TERMINAL_DOUBLE_PIPE,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_COMMA,
            WdlTerminalIdentifier.TERMINAL_GT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
            WdlTerminalIdentifier.TERMINAL_PERCENT,
            WdlTerminalIdentifier.TERMINAL_RSQUARE,
            WdlTerminalIdentifier.TERMINAL_GTEQ,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_ASTERISK,
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_DOUBLE_AMPERSAND,
            WdlTerminalIdentifier.TERMINAL_INPUT,
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
            WdlTerminalIdentifier.TERMINAL_WHILE,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_COLON,
        }));
        map.put(84, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_INPUT,
        }));
        map.put(85, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_COMMA,
        }));
        map.put(86, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RSQUARE,
        }));
        map.put(87, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
            WdlTerminalIdentifier.TERMINAL_TASK,
        }));
        map.put(88, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(89, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
            WdlTerminalIdentifier.TERMINAL_TASK,
        }));
        map.put(90, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
            WdlTerminalIdentifier.TERMINAL_INPUT,
        }));
        map.put(91, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
        }));
        map.put(92, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IMPORT,
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
            WdlTerminalIdentifier.TERMINAL_TASK,
        }));
        map.put(93, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
            WdlTerminalIdentifier.TERMINAL_WHILE,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_INPUT,
        }));
        map.put(94, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
        }));
        map.put(95, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_COMMA,
        }));
        map.put(96, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_COMMA,
        }));
        map.put(97, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(98, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RSQUARE,
            WdlTerminalIdentifier.TERMINAL_RPAREN,
        }));
        map.put(99, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WHILE,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_TYPE,
        }));
        map.put(100, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_INPUT,
        }));
        map.put(101, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        map.put(102, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        map.put(103, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RAW_CMD_END,
        }));
        map.put(104, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_STRING,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(105, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_COMMA,
        }));
        map.put(106, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WHILE,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_TYPE,
        }));
        map.put(107, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(108, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
            WdlTerminalIdentifier.TERMINAL_TASK,
        }));
        map.put(109, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RAW_CMD_END,
        }));
        map.put(110, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WHILE,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_CALL,
        }));
        map.put(111, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        map.put(112, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RSQUARE,
            WdlTerminalIdentifier.TERMINAL_RPAREN,
        }));
        map.put(113, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WHILE,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
            WdlTerminalIdentifier.TERMINAL_TYPE,
        }));
        map.put(114, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        map.put(115, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
            WdlTerminalIdentifier.TERMINAL_TASK,
        }));
        map.put(116, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WHILE,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_TYPE,
        }));
        map.put(117, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CMD_PART,
            WdlTerminalIdentifier.TERMINAL_CMD_PARAM_START,
            WdlTerminalIdentifier.TERMINAL_RAW_CMD_END,
        }));
        map.put(118, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(119, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
        }));
        map.put(120, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IMPORT,
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
            WdlTerminalIdentifier.TERMINAL_TASK,
        }));
        map.put(121, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_COMMA,
        }));
        map.put(122, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_STRING,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_CMD_ATTR_HINT,
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(123, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WHILE,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_TYPE,
        }));
        map.put(124, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WHILE,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
            WdlTerminalIdentifier.TERMINAL_TYPE,
        }));
        map.put(125, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WHILE,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_TYPE,
        }));
        map.put(126, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        map.put(127, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
            WdlTerminalIdentifier.TERMINAL_TASK,
        }));
        map.put(128, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_INPUT,
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
            WdlTerminalIdentifier.TERMINAL_WHILE,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        map.put(129, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        map.put(130, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        nonterminal_follow = Collections.unmodifiableMap(map);
    }
    static {
        Map<Integer, List<TerminalIdentifier>> map = new HashMap<Integer, List<TerminalIdentifier>>();
        map.put(0, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IMPORT,
        }));
        map.put(1, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IMPORT,
        }));
        map.put(2, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(3, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(4, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
            WdlTerminalIdentifier.TERMINAL_TASK,
        }));
        map.put(5, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
            WdlTerminalIdentifier.TERMINAL_TASK,
        }));
        map.put(6, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(7, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(8, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IMPORT,
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
            WdlTerminalIdentifier.TERMINAL_TASK,
        }));
        map.put(9, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
        }));
        map.put(10, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TASK,
        }));
        map.put(11, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_AS,
        }));
        map.put(12, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(13, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IMPORT,
        }));
        map.put(14, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_AS,
        }));
        map.put(15, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_TYPE,
        }));
        map.put(16, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_TYPE,
        }));
        map.put(17, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(18, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(19, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_META,
        }));
        map.put(20, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_META,
        }));
        map.put(21, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(22, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(23, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TASK,
        }));
        map.put(24, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
        }));
        map.put(25, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
        }));
        map.put(26, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
        }));
        map.put(27, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
        }));
        map.put(28, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_META,
        }));
        map.put(29, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CMD_PART,
            WdlTerminalIdentifier.TERMINAL_CMD_PARAM_START,
        }));
        map.put(30, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CMD_PART,
            WdlTerminalIdentifier.TERMINAL_CMD_PARAM_START,
        }));
        map.put(31, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(32, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(33, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
        }));
        map.put(34, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CMD_PART,
        }));
        map.put(35, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CMD_PARAM_START,
        }));
        map.put(36, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CMD_ATTR_HINT,
        }));
        map.put(37, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CMD_ATTR_HINT,
        }));
        map.put(38, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(39, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(40, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CMD_PARAM_START,
        }));
        map.put(41, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CMD_ATTR_HINT,
        }));
        map.put(42, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_TYPE,
        }));
        map.put(43, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_TYPE,
        }));
        map.put(44, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(45, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(46, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
        }));
        map.put(47, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_TYPE,
        }));
        map.put(48, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
        }));
        map.put(49, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
        }));
        map.put(50, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_META,
        }));
        map.put(51, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(52, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(53, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(54, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(55, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(56, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(57, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_QMARK,
            WdlTerminalIdentifier.TERMINAL_PLUS,
        }));
        map.put(58, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(59, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_EQUAL,
        }));
        map.put(60, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(61, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_TYPE,
        }));
        map.put(62, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_EQUAL,
        }));
        map.put(63, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_QMARK,
        }));
        map.put(64, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PLUS,
        }));
        map.put(65, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_STRING,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(66, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WHILE,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_CALL,
        }));
        map.put(67, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WHILE,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_CALL,
        }));
        map.put(68, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(69, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(70, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
        }));
        map.put(71, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CALL,
        }));
        map.put(72, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_TYPE,
        }));
        map.put(73, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WHILE,
        }));
        map.put(74, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IF,
        }));
        map.put(75, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_SCATTER,
        }));
        map.put(76, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
        }));
        map.put(77, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_AS,
        }));
        map.put(78, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(79, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(80, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(81, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CALL,
        }));
        map.put(82, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_INPUT,
        }));
        map.put(83, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_INPUT,
        }));
        map.put(84, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(85, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(86, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(87, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(88, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_COMMA,
        }));
        map.put(89, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(90, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(91, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_INPUT,
        }));
        map.put(92, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(93, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_AS,
        }));
        map.put(94, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_FQN,
        }));
        map.put(95, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_COMMA,
        }));
        map.put(96, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(97, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(98, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
        }));
        map.put(99, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_DOT,
        }));
        map.put(100, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(101, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_FQN,
        }));
        map.put(102, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_DOT,
        }));
        map.put(103, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WHILE,
        }));
        map.put(104, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IF,
        }));
        map.put(105, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_SCATTER,
        }));
        map.put(106, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(107, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_TYPE,
        }));
        map.put(108, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_COMMA,
        }));
        map.put(109, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(110, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(111, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE,
        }));
        map.put(112, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE,
        }));
        map.put(113, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_STRING,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(114, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_STRING,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(115, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_STRING,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(116, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_STRING,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(117, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_STRING,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(118, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_STRING,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(119, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_STRING,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(120, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_STRING,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(121, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_STRING,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(122, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_STRING,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(123, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_STRING,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(124, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_STRING,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(125, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_STRING,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(126, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_NOT,
        }));
        map.put(127, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PLUS,
        }));
        map.put(128, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_DASH,
        }));
        map.put(129, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_STRING,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(130, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_COMMA,
        }));
        map.put(131, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(132, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(133, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(134, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(135, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(136, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(137, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_COMMA,
        }));
        map.put(138, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(139, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(140, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_OBJECT,
        }));
        map.put(141, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
        }));
        map.put(142, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_STRING,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(143, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_COMMA,
        }));
        map.put(144, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(145, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(146, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(147, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_LPAREN,
        }));
        map.put(148, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_STRING,
        }));
        map.put(149, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(150, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
        }));
        map.put(151, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_INTEGER,
        }));
        map.put(152, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_FLOAT,
        }));
        rule_first = Collections.unmodifiableMap(map);
    }
    static {
        Map<Integer, List<String>> map = new HashMap<Integer, List<String>>();
        map.put(56, new ArrayList<String>());
        map.put(57, new ArrayList<String>());
        map.put(58, new ArrayList<String>());
        map.put(59, new ArrayList<String>());
        map.put(60, new ArrayList<String>());
        map.put(61, new ArrayList<String>());
        map.put(62, new ArrayList<String>());
        map.put(63, new ArrayList<String>());
        map.put(64, new ArrayList<String>());
        map.put(65, new ArrayList<String>());
        map.put(66, new ArrayList<String>());
        map.put(67, new ArrayList<String>());
        map.put(68, new ArrayList<String>());
        map.put(69, new ArrayList<String>());
        map.put(70, new ArrayList<String>());
        map.put(71, new ArrayList<String>());
        map.put(72, new ArrayList<String>());
        map.put(73, new ArrayList<String>());
        map.put(74, new ArrayList<String>());
        map.put(75, new ArrayList<String>());
        map.put(76, new ArrayList<String>());
        map.put(77, new ArrayList<String>());
        map.put(78, new ArrayList<String>());
        map.put(79, new ArrayList<String>());
        map.put(80, new ArrayList<String>());
        map.put(81, new ArrayList<String>());
        map.put(82, new ArrayList<String>());
        map.put(83, new ArrayList<String>());
        map.put(84, new ArrayList<String>());
        map.put(85, new ArrayList<String>());
        map.put(86, new ArrayList<String>());
        map.put(87, new ArrayList<String>());
        map.put(88, new ArrayList<String>());
        map.put(89, new ArrayList<String>());
        map.put(90, new ArrayList<String>());
        map.put(91, new ArrayList<String>());
        map.put(92, new ArrayList<String>());
        map.put(93, new ArrayList<String>());
        map.put(94, new ArrayList<String>());
        map.put(95, new ArrayList<String>());
        map.put(96, new ArrayList<String>());
        map.put(97, new ArrayList<String>());
        map.put(98, new ArrayList<String>());
        map.put(99, new ArrayList<String>());
        map.put(100, new ArrayList<String>());
        map.put(101, new ArrayList<String>());
        map.put(102, new ArrayList<String>());
        map.put(103, new ArrayList<String>());
        map.put(104, new ArrayList<String>());
        map.put(105, new ArrayList<String>());
        map.put(106, new ArrayList<String>());
        map.put(107, new ArrayList<String>());
        map.put(108, new ArrayList<String>());
        map.put(109, new ArrayList<String>());
        map.put(110, new ArrayList<String>());
        map.put(111, new ArrayList<String>());
        map.put(112, new ArrayList<String>());
        map.put(113, new ArrayList<String>());
        map.put(114, new ArrayList<String>());
        map.put(115, new ArrayList<String>());
        map.put(116, new ArrayList<String>());
        map.put(117, new ArrayList<String>());
        map.put(118, new ArrayList<String>());
        map.put(119, new ArrayList<String>());
        map.put(120, new ArrayList<String>());
        map.put(121, new ArrayList<String>());
        map.put(122, new ArrayList<String>());
        map.put(123, new ArrayList<String>());
        map.put(124, new ArrayList<String>());
        map.put(125, new ArrayList<String>());
        map.put(126, new ArrayList<String>());
        map.put(127, new ArrayList<String>());
        map.put(128, new ArrayList<String>());
        map.put(129, new ArrayList<String>());
        map.put(130, new ArrayList<String>());
        map.get(87).add("$_gen0 = $import $_gen1");
        map.get(108).add("$_gen1 = $import $_gen1");
        map.get(108).add("$_gen1 = :_empty");
        map.get(87).add("$_gen0 = :_empty");
        map.get(107).add("$_gen2 = $workflow_or_task $_gen3");
        map.get(77).add("$_gen3 = $workflow_or_task $_gen3");
        map.get(77).add("$_gen3 = :_empty");
        map.get(107).add("$_gen2 = :_empty");
        map.get(118).add("$document = $_gen0 $_gen2 -> Document( imports=$0, definitions=$1 )");
        map.get(115).add("$workflow_or_task = $workflow");
        map.get(115).add("$workflow_or_task = $task");
        map.get(120).add("$_gen4 = $import_namespace");
        map.get(120).add("$_gen4 = :_empty");
        map.get(92).add("$import = :import :string $_gen4 -> Import( uri=$1, namespace=$2 )");
        map.get(66).add("$import_namespace = :as :identifier -> $1");
        map.get(90).add("$_gen5 = $declaration $_gen6");
        map.get(62).add("$_gen6 = $declaration $_gen6");
        map.get(62).add("$_gen6 = :_empty");
        map.get(90).add("$_gen5 = :_empty");
        map.get(71).add("$_gen7 = $sections $_gen8");
        map.get(63).add("$_gen8 = $sections $_gen8");
        map.get(63).add("$_gen8 = :_empty");
        map.get(71).add("$_gen7 = :_empty");
        map.get(127).add("$task = :task :identifier :lbrace $_gen5 $_gen7 :rbrace -> Task( name=$1, declarations=$3, sections=$4 )");
        map.get(59).add("$sections = $command");
        map.get(59).add("$sections = $outputs");
        map.get(59).add("$sections = $runtime");
        map.get(59).add("$sections = $parameter_meta");
        map.get(59).add("$sections = $meta");
        map.get(103).add("$_gen9 = $command_part $_gen10");
        map.get(109).add("$_gen10 = $command_part $_gen10");
        map.get(109).add("$_gen10 = :_empty");
        map.get(103).add("$_gen9 = :_empty");
        map.get(91).add("$command = :raw_command :raw_cmd_start $_gen9 :raw_cmd_end -> RawCommand( parts=$2 )");
        map.get(117).add("$command_part = :cmd_part");
        map.get(117).add("$command_part = $cmd_param");
        map.get(56).add("$_gen11 = $cmd_param_kv $_gen12");
        map.get(104).add("$_gen12 = $cmd_param_kv $_gen12");
        map.get(104).add("$_gen12 = :_empty");
        map.get(56).add("$_gen11 = :_empty");
        map.get(67).add("$cmd_param = :cmd_param_start $_gen11 $e :cmd_param_end -> CommandParameter( attributes=$1, expr=$2 )");
        map.get(122).add("$cmd_param_kv = :cmd_attr_hint :identifier :equal $e -> CommandParameterAttr( key=$1, value=$3 )");
        map.get(60).add("$_gen13 = $output_kv $_gen14");
        map.get(102).add("$_gen14 = $output_kv $_gen14");
        map.get(102).add("$_gen14 = :_empty");
        map.get(60).add("$_gen13 = :_empty");
        map.get(94).add("$outputs = :output :lbrace $_gen13 :rbrace -> Outputs( attributes=$2 )");
        map.get(82).add("$output_kv = $type_e :identifier :equal $e -> Output( type=$0, var=$1, expression=$3 )");
        map.get(119).add("$runtime = :runtime $map -> Runtime( map=$1 )");
        map.get(58).add("$parameter_meta = :parameter_meta $map -> ParameterMeta( map=$1 )");
        map.get(80).add("$meta = :meta $map -> Meta( map=$1 )");
        map.get(114).add("$_gen15 = $kv $_gen16");
        map.get(74).add("$_gen16 = $kv $_gen16");
        map.get(74).add("$_gen16 = :_empty");
        map.get(114).add("$_gen15 = :_empty");
        map.get(64).add("$map = :lbrace $_gen15 :rbrace -> $1");
        map.get(88).add("$kv = :identifier :colon $e -> RuntimeAttribute( key=$0, value=$2 )");
        map.get(97).add("$_gen17 = $postfix_quantifier");
        map.get(97).add("$_gen17 = :_empty");
        map.get(128).add("$_gen18 = $setter");
        map.get(128).add("$_gen18 = :_empty");
        map.get(93).add("$declaration = $type_e $_gen17 :identifier $_gen18 -> Declaration( type=$0, postfix=$1, name=$2, expression=$3 )");
        map.get(65).add("$setter = :equal $e -> $1");
        map.get(72).add("$postfix_quantifier = :qmark");
        map.get(72).add("$postfix_quantifier = :plus");
        map.get(121).add("$map_kv = $e :colon $e -> MapLiteralKv( key=$0, value=$2 )");
        map.get(81).add("$_gen19 = $wf_body_element $_gen20");
        map.get(70).add("$_gen20 = $wf_body_element $_gen20");
        map.get(70).add("$_gen20 = :_empty");
        map.get(81).add("$_gen19 = :_empty");
        map.get(89).add("$workflow = :workflow :identifier :lbrace $_gen19 :rbrace -> Workflow( name=$1, body=$3 )");
        map.get(110).add("$wf_body_element = $call");
        map.get(110).add("$wf_body_element = $declaration");
        map.get(110).add("$wf_body_element = $while_loop");
        map.get(110).add("$wf_body_element = $if_stmt");
        map.get(110).add("$wf_body_element = $scatter");
        map.get(110).add("$wf_body_element = $wf_outputs");
        map.get(113).add("$_gen21 = $alias");
        map.get(113).add("$_gen21 = :_empty");
        map.get(61).add("$_gen22 = $call_body");
        map.get(61).add("$_gen22 = :_empty");
        map.get(99).add("$call = :call :fqn $_gen21 $_gen22 -> Call( task=$1, alias=$2, body=$3 )");
        map.get(126).add("$_gen23 = $call_input $_gen24");
        map.get(111).add("$_gen24 = $call_input $_gen24");
        map.get(111).add("$_gen24 = :_empty");
        map.get(126).add("$_gen23 = :_empty");
        map.get(116).add("$call_body = :lbrace $_gen5 $_gen23 :rbrace -> CallBody( declarations=$1, io=$2 )");
        map.get(84).add("$_gen25 = $mapping $_gen26");
        map.get(100).add("$_gen26 = :comma $mapping $_gen26");
        map.get(100).add("$_gen26 = :_empty");
        map.get(84).add("$_gen25 = :_empty");
        map.get(57).add("$call_input = :input :colon $_gen25 -> Inputs( map=$2 )");
        map.get(68).add("$mapping = :identifier :equal $e -> IOMapping( key=$0, value=$2 )");
        map.get(124).add("$alias = :as :identifier -> $1");
        map.get(129).add("$_gen27 = $wf_output $_gen28");
        map.get(69).add("$_gen28 = :comma $wf_output $_gen28");
        map.get(69).add("$_gen28 = :_empty");
        map.get(129).add("$_gen27 = :_empty");
        map.get(123).add("$wf_outputs = :output :lbrace $_gen27 :rbrace -> WorkflowOutputs( outputs=$2 )");
        map.get(95).add("$_gen29 = $wf_output_wildcard");
        map.get(95).add("$_gen29 = :_empty");
        map.get(96).add("$wf_output = :fqn $_gen29 -> WorkflowOutput( fqn=$0, wildcard=$1 )");
        map.get(105).add("$wf_output_wildcard = :dot :asterisk -> $1");
        map.get(75).add("$while_loop = :while :lparen $e :rparen :lbrace $_gen19 :rbrace -> WhileLoop( expression=$2, body=$5 )");
        map.get(125).add("$if_stmt = :if :lparen $e :rparen :lbrace $_gen19 :rbrace -> If( expression=$2, body=$5 )");
        map.get(106).add("$scatter = :scatter :lparen :identifier :in $e :rparen :lbrace $_gen19 :rbrace -> Scatter( item=$2, collection=$4, body=$7 )");
        map.get(85).add("$object_kv = :identifier :colon $e -> ObjectKV( key=$0, value=$2 )");
        map.get(78).add("$_gen30 = $type_e $_gen31");
        map.get(86).add("$_gen31 = :comma $type_e $_gen31");
        map.get(86).add("$_gen31 = :_empty");
        map.get(78).add("$_gen30 = :_empty");
        map.get(76).add("$type_e = :type <=> :lsquare $_gen30 :rsquare -> Type( name=$0, subtype=$2 )");
        map.get(76).add("$type_e = :type");
        map.get(83).add("$e = $e :double_pipe $e -> LogicalOr( lhs=$0, rhs=$2 )");
        map.get(83).add("$e = $e :double_ampersand $e -> LogicalAnd( lhs=$0, rhs=$2 )");
        map.get(83).add("$e = $e :double_equal $e -> Equals( lhs=$0, rhs=$2 )");
        map.get(83).add("$e = $e :not_equal $e -> NotEquals( lhs=$0, rhs=$2 )");
        map.get(83).add("$e = $e :lt $e -> LessThan( lhs=$0, rhs=$2 )");
        map.get(83).add("$e = $e :lteq $e -> LessThanOrEqual( lhs=$0, rhs=$2 )");
        map.get(83).add("$e = $e :gt $e -> GreaterThan( lhs=$0, rhs=$2 )");
        map.get(83).add("$e = $e :gteq $e -> GreaterThanOrEqual( lhs=$0, rhs=$2 )");
        map.get(83).add("$e = $e :plus $e -> Add( lhs=$0, rhs=$2 )");
        map.get(83).add("$e = $e :dash $e -> Subtract( lhs=$0, rhs=$2 )");
        map.get(83).add("$e = $e :asterisk $e -> Multiply( lhs=$0, rhs=$2 )");
        map.get(83).add("$e = $e :slash $e -> Divide( lhs=$0, rhs=$2 )");
        map.get(83).add("$e = $e :percent $e -> Remainder( lhs=$0, rhs=$2 )");
        map.get(83).add("$e = :not $e -> LogicalNot( expression=$1 )");
        map.get(83).add("$e = :plus $e -> UnaryPlus( expression=$1 )");
        map.get(83).add("$e = :dash $e -> UnaryNegation( expression=$1 )");
        map.get(112).add("$_gen32 = $e $_gen33");
        map.get(98).add("$_gen33 = :comma $e $_gen33");
        map.get(98).add("$_gen33 = :_empty");
        map.get(112).add("$_gen32 = :_empty");
        map.get(83).add("$e = :identifier <=> :lparen $_gen32 :rparen -> FunctionCall( name=$0, params=$2 )");
        map.get(83).add("$e = :identifier <=> :lsquare $e :rsquare -> ArrayOrMapLookup( lhs=$0, rhs=$2 )");
        map.get(83).add("$e = :identifier <=> :dot :identifier -> MemberAccess( lhs=$0, rhs=$2 )");
        map.get(130).add("$_gen34 = $object_kv $_gen35");
        map.get(73).add("$_gen35 = :comma $object_kv $_gen35");
        map.get(73).add("$_gen35 = :_empty");
        map.get(130).add("$_gen34 = :_empty");
        map.get(83).add("$e = :object :lbrace $_gen34 :rbrace -> ObjectLiteral( map=$2 )");
        map.get(83).add("$e = :lsquare $_gen32 :rsquare -> ArrayLiteral( values=$1 )");
        map.get(79).add("$_gen36 = $map_kv $_gen37");
        map.get(101).add("$_gen37 = :comma $map_kv $_gen37");
        map.get(101).add("$_gen37 = :_empty");
        map.get(79).add("$_gen36 = :_empty");
        map.get(83).add("$e = :lbrace $_gen36 :rbrace -> MapLiteral( map=$1 )");
        map.get(83).add("$e = :lparen $e :rparen -> $1");
        map.get(83).add("$e = :string");
        map.get(83).add("$e = :identifier");
        map.get(83).add("$e = :boolean");
        map.get(83).add("$e = :integer");
        map.get(83).add("$e = :float");
        nonterminal_rules = Collections.unmodifiableMap(map);
    }
    static {
        Map<Integer, String> map = new HashMap<Integer, String>();
        map.put(new Integer(0), "$_gen0 = $import $_gen1");
        map.put(new Integer(1), "$_gen1 = $import $_gen1");
        map.put(new Integer(2), "$_gen1 = :_empty");
        map.put(new Integer(3), "$_gen0 = :_empty");
        map.put(new Integer(4), "$_gen2 = $workflow_or_task $_gen3");
        map.put(new Integer(5), "$_gen3 = $workflow_or_task $_gen3");
        map.put(new Integer(6), "$_gen3 = :_empty");
        map.put(new Integer(7), "$_gen2 = :_empty");
        map.put(new Integer(8), "$document = $_gen0 $_gen2 -> Document( imports=$0, definitions=$1 )");
        map.put(new Integer(9), "$workflow_or_task = $workflow");
        map.put(new Integer(10), "$workflow_or_task = $task");
        map.put(new Integer(11), "$_gen4 = $import_namespace");
        map.put(new Integer(12), "$_gen4 = :_empty");
        map.put(new Integer(13), "$import = :import :string $_gen4 -> Import( uri=$1, namespace=$2 )");
        map.put(new Integer(14), "$import_namespace = :as :identifier -> $1");
        map.put(new Integer(15), "$_gen5 = $declaration $_gen6");
        map.put(new Integer(16), "$_gen6 = $declaration $_gen6");
        map.put(new Integer(17), "$_gen6 = :_empty");
        map.put(new Integer(18), "$_gen5 = :_empty");
        map.put(new Integer(19), "$_gen7 = $sections $_gen8");
        map.put(new Integer(20), "$_gen8 = $sections $_gen8");
        map.put(new Integer(21), "$_gen8 = :_empty");
        map.put(new Integer(22), "$_gen7 = :_empty");
        map.put(new Integer(23), "$task = :task :identifier :lbrace $_gen5 $_gen7 :rbrace -> Task( name=$1, declarations=$3, sections=$4 )");
        map.put(new Integer(24), "$sections = $command");
        map.put(new Integer(25), "$sections = $outputs");
        map.put(new Integer(26), "$sections = $runtime");
        map.put(new Integer(27), "$sections = $parameter_meta");
        map.put(new Integer(28), "$sections = $meta");
        map.put(new Integer(29), "$_gen9 = $command_part $_gen10");
        map.put(new Integer(30), "$_gen10 = $command_part $_gen10");
        map.put(new Integer(31), "$_gen10 = :_empty");
        map.put(new Integer(32), "$_gen9 = :_empty");
        map.put(new Integer(33), "$command = :raw_command :raw_cmd_start $_gen9 :raw_cmd_end -> RawCommand( parts=$2 )");
        map.put(new Integer(34), "$command_part = :cmd_part");
        map.put(new Integer(35), "$command_part = $cmd_param");
        map.put(new Integer(36), "$_gen11 = $cmd_param_kv $_gen12");
        map.put(new Integer(37), "$_gen12 = $cmd_param_kv $_gen12");
        map.put(new Integer(38), "$_gen12 = :_empty");
        map.put(new Integer(39), "$_gen11 = :_empty");
        map.put(new Integer(40), "$cmd_param = :cmd_param_start $_gen11 $e :cmd_param_end -> CommandParameter( attributes=$1, expr=$2 )");
        map.put(new Integer(41), "$cmd_param_kv = :cmd_attr_hint :identifier :equal $e -> CommandParameterAttr( key=$1, value=$3 )");
        map.put(new Integer(42), "$_gen13 = $output_kv $_gen14");
        map.put(new Integer(43), "$_gen14 = $output_kv $_gen14");
        map.put(new Integer(44), "$_gen14 = :_empty");
        map.put(new Integer(45), "$_gen13 = :_empty");
        map.put(new Integer(46), "$outputs = :output :lbrace $_gen13 :rbrace -> Outputs( attributes=$2 )");
        map.put(new Integer(47), "$output_kv = $type_e :identifier :equal $e -> Output( type=$0, var=$1, expression=$3 )");
        map.put(new Integer(48), "$runtime = :runtime $map -> Runtime( map=$1 )");
        map.put(new Integer(49), "$parameter_meta = :parameter_meta $map -> ParameterMeta( map=$1 )");
        map.put(new Integer(50), "$meta = :meta $map -> Meta( map=$1 )");
        map.put(new Integer(51), "$_gen15 = $kv $_gen16");
        map.put(new Integer(52), "$_gen16 = $kv $_gen16");
        map.put(new Integer(53), "$_gen16 = :_empty");
        map.put(new Integer(54), "$_gen15 = :_empty");
        map.put(new Integer(55), "$map = :lbrace $_gen15 :rbrace -> $1");
        map.put(new Integer(56), "$kv = :identifier :colon $e -> RuntimeAttribute( key=$0, value=$2 )");
        map.put(new Integer(57), "$_gen17 = $postfix_quantifier");
        map.put(new Integer(58), "$_gen17 = :_empty");
        map.put(new Integer(59), "$_gen18 = $setter");
        map.put(new Integer(60), "$_gen18 = :_empty");
        map.put(new Integer(61), "$declaration = $type_e $_gen17 :identifier $_gen18 -> Declaration( type=$0, postfix=$1, name=$2, expression=$3 )");
        map.put(new Integer(62), "$setter = :equal $e -> $1");
        map.put(new Integer(63), "$postfix_quantifier = :qmark");
        map.put(new Integer(64), "$postfix_quantifier = :plus");
        map.put(new Integer(65), "$map_kv = $e :colon $e -> MapLiteralKv( key=$0, value=$2 )");
        map.put(new Integer(66), "$_gen19 = $wf_body_element $_gen20");
        map.put(new Integer(67), "$_gen20 = $wf_body_element $_gen20");
        map.put(new Integer(68), "$_gen20 = :_empty");
        map.put(new Integer(69), "$_gen19 = :_empty");
        map.put(new Integer(70), "$workflow = :workflow :identifier :lbrace $_gen19 :rbrace -> Workflow( name=$1, body=$3 )");
        map.put(new Integer(71), "$wf_body_element = $call");
        map.put(new Integer(72), "$wf_body_element = $declaration");
        map.put(new Integer(73), "$wf_body_element = $while_loop");
        map.put(new Integer(74), "$wf_body_element = $if_stmt");
        map.put(new Integer(75), "$wf_body_element = $scatter");
        map.put(new Integer(76), "$wf_body_element = $wf_outputs");
        map.put(new Integer(77), "$_gen21 = $alias");
        map.put(new Integer(78), "$_gen21 = :_empty");
        map.put(new Integer(79), "$_gen22 = $call_body");
        map.put(new Integer(80), "$_gen22 = :_empty");
        map.put(new Integer(81), "$call = :call :fqn $_gen21 $_gen22 -> Call( task=$1, alias=$2, body=$3 )");
        map.put(new Integer(82), "$_gen23 = $call_input $_gen24");
        map.put(new Integer(83), "$_gen24 = $call_input $_gen24");
        map.put(new Integer(84), "$_gen24 = :_empty");
        map.put(new Integer(85), "$_gen23 = :_empty");
        map.put(new Integer(86), "$call_body = :lbrace $_gen5 $_gen23 :rbrace -> CallBody( declarations=$1, io=$2 )");
        map.put(new Integer(87), "$_gen25 = $mapping $_gen26");
        map.put(new Integer(88), "$_gen26 = :comma $mapping $_gen26");
        map.put(new Integer(89), "$_gen26 = :_empty");
        map.put(new Integer(90), "$_gen25 = :_empty");
        map.put(new Integer(91), "$call_input = :input :colon $_gen25 -> Inputs( map=$2 )");
        map.put(new Integer(92), "$mapping = :identifier :equal $e -> IOMapping( key=$0, value=$2 )");
        map.put(new Integer(93), "$alias = :as :identifier -> $1");
        map.put(new Integer(94), "$_gen27 = $wf_output $_gen28");
        map.put(new Integer(95), "$_gen28 = :comma $wf_output $_gen28");
        map.put(new Integer(96), "$_gen28 = :_empty");
        map.put(new Integer(97), "$_gen27 = :_empty");
        map.put(new Integer(98), "$wf_outputs = :output :lbrace $_gen27 :rbrace -> WorkflowOutputs( outputs=$2 )");
        map.put(new Integer(99), "$_gen29 = $wf_output_wildcard");
        map.put(new Integer(100), "$_gen29 = :_empty");
        map.put(new Integer(101), "$wf_output = :fqn $_gen29 -> WorkflowOutput( fqn=$0, wildcard=$1 )");
        map.put(new Integer(102), "$wf_output_wildcard = :dot :asterisk -> $1");
        map.put(new Integer(103), "$while_loop = :while :lparen $e :rparen :lbrace $_gen19 :rbrace -> WhileLoop( expression=$2, body=$5 )");
        map.put(new Integer(104), "$if_stmt = :if :lparen $e :rparen :lbrace $_gen19 :rbrace -> If( expression=$2, body=$5 )");
        map.put(new Integer(105), "$scatter = :scatter :lparen :identifier :in $e :rparen :lbrace $_gen19 :rbrace -> Scatter( item=$2, collection=$4, body=$7 )");
        map.put(new Integer(106), "$object_kv = :identifier :colon $e -> ObjectKV( key=$0, value=$2 )");
        map.put(new Integer(107), "$_gen30 = $type_e $_gen31");
        map.put(new Integer(108), "$_gen31 = :comma $type_e $_gen31");
        map.put(new Integer(109), "$_gen31 = :_empty");
        map.put(new Integer(110), "$_gen30 = :_empty");
        map.put(new Integer(111), "$type_e = :type <=> :lsquare $_gen30 :rsquare -> Type( name=$0, subtype=$2 )");
        map.put(new Integer(112), "$type_e = :type");
        map.put(new Integer(113), "$e = $e :double_pipe $e -> LogicalOr( lhs=$0, rhs=$2 )");
        map.put(new Integer(114), "$e = $e :double_ampersand $e -> LogicalAnd( lhs=$0, rhs=$2 )");
        map.put(new Integer(115), "$e = $e :double_equal $e -> Equals( lhs=$0, rhs=$2 )");
        map.put(new Integer(116), "$e = $e :not_equal $e -> NotEquals( lhs=$0, rhs=$2 )");
        map.put(new Integer(117), "$e = $e :lt $e -> LessThan( lhs=$0, rhs=$2 )");
        map.put(new Integer(118), "$e = $e :lteq $e -> LessThanOrEqual( lhs=$0, rhs=$2 )");
        map.put(new Integer(119), "$e = $e :gt $e -> GreaterThan( lhs=$0, rhs=$2 )");
        map.put(new Integer(120), "$e = $e :gteq $e -> GreaterThanOrEqual( lhs=$0, rhs=$2 )");
        map.put(new Integer(121), "$e = $e :plus $e -> Add( lhs=$0, rhs=$2 )");
        map.put(new Integer(122), "$e = $e :dash $e -> Subtract( lhs=$0, rhs=$2 )");
        map.put(new Integer(123), "$e = $e :asterisk $e -> Multiply( lhs=$0, rhs=$2 )");
        map.put(new Integer(124), "$e = $e :slash $e -> Divide( lhs=$0, rhs=$2 )");
        map.put(new Integer(125), "$e = $e :percent $e -> Remainder( lhs=$0, rhs=$2 )");
        map.put(new Integer(126), "$e = :not $e -> LogicalNot( expression=$1 )");
        map.put(new Integer(127), "$e = :plus $e -> UnaryPlus( expression=$1 )");
        map.put(new Integer(128), "$e = :dash $e -> UnaryNegation( expression=$1 )");
        map.put(new Integer(129), "$_gen32 = $e $_gen33");
        map.put(new Integer(130), "$_gen33 = :comma $e $_gen33");
        map.put(new Integer(131), "$_gen33 = :_empty");
        map.put(new Integer(132), "$_gen32 = :_empty");
        map.put(new Integer(133), "$e = :identifier <=> :lparen $_gen32 :rparen -> FunctionCall( name=$0, params=$2 )");
        map.put(new Integer(134), "$e = :identifier <=> :lsquare $e :rsquare -> ArrayOrMapLookup( lhs=$0, rhs=$2 )");
        map.put(new Integer(135), "$e = :identifier <=> :dot :identifier -> MemberAccess( lhs=$0, rhs=$2 )");
        map.put(new Integer(136), "$_gen34 = $object_kv $_gen35");
        map.put(new Integer(137), "$_gen35 = :comma $object_kv $_gen35");
        map.put(new Integer(138), "$_gen35 = :_empty");
        map.put(new Integer(139), "$_gen34 = :_empty");
        map.put(new Integer(140), "$e = :object :lbrace $_gen34 :rbrace -> ObjectLiteral( map=$2 )");
        map.put(new Integer(141), "$e = :lsquare $_gen32 :rsquare -> ArrayLiteral( values=$1 )");
        map.put(new Integer(142), "$_gen36 = $map_kv $_gen37");
        map.put(new Integer(143), "$_gen37 = :comma $map_kv $_gen37");
        map.put(new Integer(144), "$_gen37 = :_empty");
        map.put(new Integer(145), "$_gen36 = :_empty");
        map.put(new Integer(146), "$e = :lbrace $_gen36 :rbrace -> MapLiteral( map=$1 )");
        map.put(new Integer(147), "$e = :lparen $e :rparen -> $1");
        map.put(new Integer(148), "$e = :string");
        map.put(new Integer(149), "$e = :identifier");
        map.put(new Integer(150), "$e = :boolean");
        map.put(new Integer(151), "$e = :integer");
        map.put(new Integer(152), "$e = :float");
        rules = Collections.unmodifiableMap(map);
    }
    public static boolean is_terminal(int id) {
        return 0 <= id && id <= 55;
    }
    public ParseTree parse(TokenStream tokens) throws SyntaxError {
        return parse(tokens, new DefaultSyntaxErrorFormatter());
    }
    public ParseTree parse(List<Terminal> tokens) throws SyntaxError {
        return parse(new TokenStream(tokens));
    }
    public ParseTree parse(TokenStream tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(tokens, error_formatter);
        ParseTree tree = parse_document(ctx);
        if (ctx.tokens.current() != null) {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            throw new SyntaxError(ctx.error_formatter.excessTokens(stack[1].getMethodName(), ctx.tokens.current()));
        }
        return tree;
    }
    public ParseTree parse(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        return parse(new TokenStream(tokens), error_formatter);
    }
    private static Terminal expect(ParserContext ctx, TerminalIdentifier expecting) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.noMoreTokens(ctx.nonterminal, expecting, ctx.tokens.last()));
        }
        if (current.getId() != expecting.id()) {
            ArrayList<TerminalIdentifier> expectedList = new ArrayList<TerminalIdentifier>();
            expectedList.add(expecting);
            throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(ctx.nonterminal, current, expectedList, ctx.rule));
        }
        Terminal next = ctx.tokens.advance();
        if ( next != null && !is_terminal(next.getId()) ) {
            throw new SyntaxError(ctx.error_formatter.invalidTerminal(ctx.nonterminal, next));
        }
        return current;
    }
    private static Map<Integer, Integer> infix_binding_power_type_e;
    private static Map<Integer, Integer> prefix_binding_power_type_e;
    static {
        Map<Integer, Integer> map = new HashMap<Integer, Integer>();
        map.put(49, 1000); /* $type_e = :type <=> :lsquare list(nt=$type_e, sep=:comma, min=0, sep_terminates=False) :rsquare -> Type( name=$0, subtype=$2 ) */
        infix_binding_power_type_e = Collections.unmodifiableMap(map);
    }
    static {
        Map<Integer, Integer> map = new HashMap<Integer, Integer>();
        prefix_binding_power_type_e = Collections.unmodifiableMap(map);
    }
    static int get_infix_binding_power_type_e(int terminal_id) {
        if (infix_binding_power_type_e.containsKey(terminal_id)) {
            return infix_binding_power_type_e.get(terminal_id);
        }
        return 0;
    }
    static int get_prefix_binding_power_type_e(int terminal_id) {
        if (prefix_binding_power_type_e.containsKey(terminal_id)) {
            return prefix_binding_power_type_e.get(terminal_id);
        }
        return 0;
    }
    public ParseTree parse_type_e(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_type_e_internal(ctx, 0);
    }
    public static ParseTree parse_type_e(ParserContext ctx) throws SyntaxError {
        return parse_type_e_internal(ctx, 0);
    }
    public static ParseTree parse_type_e_internal(ParserContext ctx, int rbp) throws SyntaxError {
        ParseTree left = nud_type_e(ctx);
        if ( left instanceof ParseTree ) {
            left.setExpr(true);
            left.setNud(true);
        }
        while (ctx.tokens.current() != null && rbp < get_infix_binding_power_type_e(ctx.tokens.current().getId())) {
            left = led_type_e(left, ctx);
        }
        if (left != null) {
            left.setExpr(true);
        }
        return left;
    }
    private static ParseTree nud_type_e(ParserContext ctx) throws SyntaxError {
        ParseTree tree = new ParseTree( new NonTerminal(76, "type_e") );
        Terminal current = ctx.tokens.current();
        ctx.nonterminal = "type_e";
        if (current == null) {
            return tree;
        }
        if (rule_first.get(111).contains(terminal_map.get(current.getId()))) {
            /* (111) $type_e = :type <=> :lsquare $_gen30 :rsquare -> Type( name=$0, subtype=$2 ) */
            ctx.rule = rules.get(111);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            tree.setNudMorphemeCount(1);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_TYPE));
        }
        else if (rule_first.get(112).contains(terminal_map.get(current.getId()))) {
            /* (112) $type_e = :type */
            ctx.rule = rules.get(112);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            tree.setNudMorphemeCount(1);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_TYPE));
        }
        return tree;
    }
    private static ParseTree led_type_e(ParseTree left, ParserContext ctx) throws SyntaxError {
        ParseTree tree = new ParseTree( new NonTerminal(76, "type_e") );
        Terminal current = ctx.tokens.current();
        ctx.nonterminal = "type_e";
        int modifier;
        if (current.getId() == 49) {
            /* $type_e = :type <=> :lsquare $_gen30 :rsquare -> Type( name=$0, subtype=$2 ) */
            ctx.rule = rules.get(111);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("name", 0);
            parameters.put("subtype", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("Type", parameters));
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_LSQUARE));
            tree.add(parse__gen30(ctx));
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_RSQUARE));
            return tree;
        }
        return tree;
    }
    private static Map<Integer, Integer> infix_binding_power_e;
    private static Map<Integer, Integer> prefix_binding_power_e;
    static {
        Map<Integer, Integer> map = new HashMap<Integer, Integer>();
        map.put(20, 2000); /* $e = $e :double_pipe $e -> LogicalOr( lhs=$0, rhs=$2 ) */
        map.put(40, 3000); /* $e = $e :double_ampersand $e -> LogicalAnd( lhs=$0, rhs=$2 ) */
        map.put(7, 4000); /* $e = $e :double_equal $e -> Equals( lhs=$0, rhs=$2 ) */
        map.put(3, 4000); /* $e = $e :not_equal $e -> NotEquals( lhs=$0, rhs=$2 ) */
        map.put(8, 5000); /* $e = $e :lt $e -> LessThan( lhs=$0, rhs=$2 ) */
        map.put(4, 5000); /* $e = $e :lteq $e -> LessThanOrEqual( lhs=$0, rhs=$2 ) */
        map.put(24, 5000); /* $e = $e :gt $e -> GreaterThan( lhs=$0, rhs=$2 ) */
        map.put(30, 5000); /* $e = $e :gteq $e -> GreaterThanOrEqual( lhs=$0, rhs=$2 ) */
        map.put(11, 6000); /* $e = $e :plus $e -> Add( lhs=$0, rhs=$2 ) */
        map.put(37, 6000); /* $e = $e :dash $e -> Subtract( lhs=$0, rhs=$2 ) */
        map.put(34, 7000); /* $e = $e :asterisk $e -> Multiply( lhs=$0, rhs=$2 ) */
        map.put(19, 7000); /* $e = $e :slash $e -> Divide( lhs=$0, rhs=$2 ) */
        map.put(28, 7000); /* $e = $e :percent $e -> Remainder( lhs=$0, rhs=$2 ) */
        map.put(33, 9000); /* $e = :identifier <=> :lparen list(nt=$e, sep=:comma, min=0, sep_terminates=False) :rparen -> FunctionCall( name=$0, params=$2 ) */
        map.put(49, 10000); /* $e = :identifier <=> :lsquare $e :rsquare -> ArrayOrMapLookup( lhs=$0, rhs=$2 ) */
        map.put(46, 11000); /* $e = :identifier <=> :dot :identifier -> MemberAccess( lhs=$0, rhs=$2 ) */
        infix_binding_power_e = Collections.unmodifiableMap(map);
    }
    static {
        Map<Integer, Integer> map = new HashMap<Integer, Integer>();
        map.put(18, 8000); /* $e = :not $e -> LogicalNot( expression=$1 ) */
        map.put(11, 8000); /* $e = :plus $e -> UnaryPlus( expression=$1 ) */
        map.put(37, 8000); /* $e = :dash $e -> UnaryNegation( expression=$1 ) */
        prefix_binding_power_e = Collections.unmodifiableMap(map);
    }
    static int get_infix_binding_power_e(int terminal_id) {
        if (infix_binding_power_e.containsKey(terminal_id)) {
            return infix_binding_power_e.get(terminal_id);
        }
        return 0;
    }
    static int get_prefix_binding_power_e(int terminal_id) {
        if (prefix_binding_power_e.containsKey(terminal_id)) {
            return prefix_binding_power_e.get(terminal_id);
        }
        return 0;
    }
    public ParseTree parse_e(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_e_internal(ctx, 0);
    }
    public static ParseTree parse_e(ParserContext ctx) throws SyntaxError {
        return parse_e_internal(ctx, 0);
    }
    public static ParseTree parse_e_internal(ParserContext ctx, int rbp) throws SyntaxError {
        ParseTree left = nud_e(ctx);
        if ( left instanceof ParseTree ) {
            left.setExpr(true);
            left.setNud(true);
        }
        while (ctx.tokens.current() != null && rbp < get_infix_binding_power_e(ctx.tokens.current().getId())) {
            left = led_e(left, ctx);
        }
        if (left != null) {
            left.setExpr(true);
        }
        return left;
    }
    private static ParseTree nud_e(ParserContext ctx) throws SyntaxError {
        ParseTree tree = new ParseTree( new NonTerminal(83, "e") );
        Terminal current = ctx.tokens.current();
        ctx.nonterminal = "e";
        if (current == null) {
            return tree;
        }
        else if (rule_first.get(126).contains(terminal_map.get(current.getId()))) {
            /* (126) $e = :not $e -> LogicalNot( expression=$1 ) */
            ctx.rule = rules.get(126);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("expression", 1);
            tree.setAstTransformation(new AstTransformNodeCreator("LogicalNot", parameters));
            tree.setNudMorphemeCount(2);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_NOT));
            tree.add(parse_e_internal(ctx, get_prefix_binding_power_e(18)));
            tree.setPrefix(true);
        }
        else if (rule_first.get(127).contains(terminal_map.get(current.getId()))) {
            /* (127) $e = :plus $e -> UnaryPlus( expression=$1 ) */
            ctx.rule = rules.get(127);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("expression", 1);
            tree.setAstTransformation(new AstTransformNodeCreator("UnaryPlus", parameters));
            tree.setNudMorphemeCount(2);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_PLUS));
            tree.add(parse_e_internal(ctx, get_prefix_binding_power_e(11)));
            tree.setPrefix(true);
        }
        else if (rule_first.get(128).contains(terminal_map.get(current.getId()))) {
            /* (128) $e = :dash $e -> UnaryNegation( expression=$1 ) */
            ctx.rule = rules.get(128);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("expression", 1);
            tree.setAstTransformation(new AstTransformNodeCreator("UnaryNegation", parameters));
            tree.setNudMorphemeCount(2);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_DASH));
            tree.add(parse_e_internal(ctx, get_prefix_binding_power_e(37)));
            tree.setPrefix(true);
        }
        else if (rule_first.get(133).contains(terminal_map.get(current.getId()))) {
            /* (133) $e = :identifier <=> :lparen $_gen32 :rparen -> FunctionCall( name=$0, params=$2 ) */
            ctx.rule = rules.get(133);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            tree.setNudMorphemeCount(1);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_IDENTIFIER));
        }
        else if (rule_first.get(134).contains(terminal_map.get(current.getId()))) {
            /* (134) $e = :identifier <=> :lsquare $e :rsquare -> ArrayOrMapLookup( lhs=$0, rhs=$2 ) */
            ctx.rule = rules.get(134);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            tree.setNudMorphemeCount(1);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_IDENTIFIER));
        }
        else if (rule_first.get(135).contains(terminal_map.get(current.getId()))) {
            /* (135) $e = :identifier <=> :dot :identifier -> MemberAccess( lhs=$0, rhs=$2 ) */
            ctx.rule = rules.get(135);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            tree.setNudMorphemeCount(1);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_IDENTIFIER));
        }
        else if (rule_first.get(140).contains(terminal_map.get(current.getId()))) {
            /* (140) $e = :object :lbrace $_gen34 :rbrace -> ObjectLiteral( map=$2 ) */
            ctx.rule = rules.get(140);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("map", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("ObjectLiteral", parameters));
            tree.setNudMorphemeCount(4);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_OBJECT));
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_LBRACE));
            tree.add(parse__gen34(ctx));
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_RBRACE));
        }
        else if (rule_first.get(141).contains(terminal_map.get(current.getId()))) {
            /* (141) $e = :lsquare $_gen32 :rsquare -> ArrayLiteral( values=$1 ) */
            ctx.rule = rules.get(141);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("values", 1);
            tree.setAstTransformation(new AstTransformNodeCreator("ArrayLiteral", parameters));
            tree.setNudMorphemeCount(3);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_LSQUARE));
            tree.add(parse__gen32(ctx));
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_RSQUARE));
        }
        else if (rule_first.get(146).contains(terminal_map.get(current.getId()))) {
            /* (146) $e = :lbrace $_gen36 :rbrace -> MapLiteral( map=$1 ) */
            ctx.rule = rules.get(146);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("map", 1);
            tree.setAstTransformation(new AstTransformNodeCreator("MapLiteral", parameters));
            tree.setNudMorphemeCount(3);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_LBRACE));
            tree.add(parse__gen36(ctx));
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_RBRACE));
        }
        else if (rule_first.get(147).contains(terminal_map.get(current.getId()))) {
            /* (147) $e = :lparen $e :rparen -> $1 */
            ctx.rule = rules.get(147);
            tree.setAstTransformation(new AstTransformSubstitution(1));
            tree.setNudMorphemeCount(3);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_LPAREN));
            tree.add(parse_e(ctx));
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_RPAREN));
        }
        else if (rule_first.get(148).contains(terminal_map.get(current.getId()))) {
            /* (148) $e = :string */
            ctx.rule = rules.get(148);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            tree.setNudMorphemeCount(1);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_STRING));
        }
        else if (rule_first.get(149).contains(terminal_map.get(current.getId()))) {
            /* (149) $e = :identifier */
            ctx.rule = rules.get(149);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            tree.setNudMorphemeCount(1);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_IDENTIFIER));
        }
        else if (rule_first.get(150).contains(terminal_map.get(current.getId()))) {
            /* (150) $e = :boolean */
            ctx.rule = rules.get(150);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            tree.setNudMorphemeCount(1);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_BOOLEAN));
        }
        else if (rule_first.get(151).contains(terminal_map.get(current.getId()))) {
            /* (151) $e = :integer */
            ctx.rule = rules.get(151);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            tree.setNudMorphemeCount(1);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_INTEGER));
        }
        else if (rule_first.get(152).contains(terminal_map.get(current.getId()))) {
            /* (152) $e = :float */
            ctx.rule = rules.get(152);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            tree.setNudMorphemeCount(1);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_FLOAT));
        }
        return tree;
    }
    private static ParseTree led_e(ParseTree left, ParserContext ctx) throws SyntaxError {
        ParseTree tree = new ParseTree( new NonTerminal(83, "e") );
        Terminal current = ctx.tokens.current();
        ctx.nonterminal = "e";
        int modifier;
        if (current.getId() == 20) {
            /* $e = $e :double_pipe $e -> LogicalOr( lhs=$0, rhs=$2 ) */
            ctx.rule = rules.get(113);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("lhs", 0);
            parameters.put("rhs", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("LogicalOr", parameters));
            tree.setExprNud(true);
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_DOUBLE_PIPE));
            modifier = 0;
            tree.setInfix(true);
            tree.add(parse_e_internal(ctx, get_infix_binding_power_e(20) - modifier));
            return tree;
        }
        if (current.getId() == 40) {
            /* $e = $e :double_ampersand $e -> LogicalAnd( lhs=$0, rhs=$2 ) */
            ctx.rule = rules.get(114);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("lhs", 0);
            parameters.put("rhs", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("LogicalAnd", parameters));
            tree.setExprNud(true);
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_DOUBLE_AMPERSAND));
            modifier = 0;
            tree.setInfix(true);
            tree.add(parse_e_internal(ctx, get_infix_binding_power_e(40) - modifier));
            return tree;
        }
        if (current.getId() == 7) {
            /* $e = $e :double_equal $e -> Equals( lhs=$0, rhs=$2 ) */
            ctx.rule = rules.get(115);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("lhs", 0);
            parameters.put("rhs", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("Equals", parameters));
            tree.setExprNud(true);
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_DOUBLE_EQUAL));
            modifier = 0;
            tree.setInfix(true);
            tree.add(parse_e_internal(ctx, get_infix_binding_power_e(7) - modifier));
            return tree;
        }
        if (current.getId() == 3) {
            /* $e = $e :not_equal $e -> NotEquals( lhs=$0, rhs=$2 ) */
            ctx.rule = rules.get(116);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("lhs", 0);
            parameters.put("rhs", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("NotEquals", parameters));
            tree.setExprNud(true);
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_NOT_EQUAL));
            modifier = 0;
            tree.setInfix(true);
            tree.add(parse_e_internal(ctx, get_infix_binding_power_e(3) - modifier));
            return tree;
        }
        if (current.getId() == 8) {
            /* $e = $e :lt $e -> LessThan( lhs=$0, rhs=$2 ) */
            ctx.rule = rules.get(117);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("lhs", 0);
            parameters.put("rhs", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("LessThan", parameters));
            tree.setExprNud(true);
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_LT));
            modifier = 0;
            tree.setInfix(true);
            tree.add(parse_e_internal(ctx, get_infix_binding_power_e(8) - modifier));
            return tree;
        }
        if (current.getId() == 4) {
            /* $e = $e :lteq $e -> LessThanOrEqual( lhs=$0, rhs=$2 ) */
            ctx.rule = rules.get(118);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("lhs", 0);
            parameters.put("rhs", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("LessThanOrEqual", parameters));
            tree.setExprNud(true);
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_LTEQ));
            modifier = 0;
            tree.setInfix(true);
            tree.add(parse_e_internal(ctx, get_infix_binding_power_e(4) - modifier));
            return tree;
        }
        if (current.getId() == 24) {
            /* $e = $e :gt $e -> GreaterThan( lhs=$0, rhs=$2 ) */
            ctx.rule = rules.get(119);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("lhs", 0);
            parameters.put("rhs", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("GreaterThan", parameters));
            tree.setExprNud(true);
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_GT));
            modifier = 0;
            tree.setInfix(true);
            tree.add(parse_e_internal(ctx, get_infix_binding_power_e(24) - modifier));
            return tree;
        }
        if (current.getId() == 30) {
            /* $e = $e :gteq $e -> GreaterThanOrEqual( lhs=$0, rhs=$2 ) */
            ctx.rule = rules.get(120);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("lhs", 0);
            parameters.put("rhs", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("GreaterThanOrEqual", parameters));
            tree.setExprNud(true);
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_GTEQ));
            modifier = 0;
            tree.setInfix(true);
            tree.add(parse_e_internal(ctx, get_infix_binding_power_e(30) - modifier));
            return tree;
        }
        if (current.getId() == 11) {
            /* $e = $e :plus $e -> Add( lhs=$0, rhs=$2 ) */
            ctx.rule = rules.get(121);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("lhs", 0);
            parameters.put("rhs", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("Add", parameters));
            tree.setExprNud(true);
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_PLUS));
            modifier = 0;
            tree.setInfix(true);
            tree.add(parse_e_internal(ctx, get_infix_binding_power_e(11) - modifier));
            return tree;
        }
        if (current.getId() == 37) {
            /* $e = $e :dash $e -> Subtract( lhs=$0, rhs=$2 ) */
            ctx.rule = rules.get(122);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("lhs", 0);
            parameters.put("rhs", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("Subtract", parameters));
            tree.setExprNud(true);
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_DASH));
            modifier = 0;
            tree.setInfix(true);
            tree.add(parse_e_internal(ctx, get_infix_binding_power_e(37) - modifier));
            return tree;
        }
        if (current.getId() == 34) {
            /* $e = $e :asterisk $e -> Multiply( lhs=$0, rhs=$2 ) */
            ctx.rule = rules.get(123);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("lhs", 0);
            parameters.put("rhs", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("Multiply", parameters));
            tree.setExprNud(true);
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_ASTERISK));
            modifier = 0;
            tree.setInfix(true);
            tree.add(parse_e_internal(ctx, get_infix_binding_power_e(34) - modifier));
            return tree;
        }
        if (current.getId() == 19) {
            /* $e = $e :slash $e -> Divide( lhs=$0, rhs=$2 ) */
            ctx.rule = rules.get(124);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("lhs", 0);
            parameters.put("rhs", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("Divide", parameters));
            tree.setExprNud(true);
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_SLASH));
            modifier = 0;
            tree.setInfix(true);
            tree.add(parse_e_internal(ctx, get_infix_binding_power_e(19) - modifier));
            return tree;
        }
        if (current.getId() == 28) {
            /* $e = $e :percent $e -> Remainder( lhs=$0, rhs=$2 ) */
            ctx.rule = rules.get(125);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("lhs", 0);
            parameters.put("rhs", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("Remainder", parameters));
            tree.setExprNud(true);
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_PERCENT));
            modifier = 0;
            tree.setInfix(true);
            tree.add(parse_e_internal(ctx, get_infix_binding_power_e(28) - modifier));
            return tree;
        }
        if (current.getId() == 33) {
            /* $e = :identifier <=> :lparen $_gen32 :rparen -> FunctionCall( name=$0, params=$2 ) */
            ctx.rule = rules.get(133);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("name", 0);
            parameters.put("params", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("FunctionCall", parameters));
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_LPAREN));
            tree.add(parse__gen32(ctx));
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_RPAREN));
            return tree;
        }
        if (current.getId() == 49) {
            /* $e = :identifier <=> :lsquare $e :rsquare -> ArrayOrMapLookup( lhs=$0, rhs=$2 ) */
            ctx.rule = rules.get(134);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("lhs", 0);
            parameters.put("rhs", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("ArrayOrMapLookup", parameters));
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_LSQUARE));
            modifier = 0;
            tree.add(parse_e_internal(ctx, get_infix_binding_power_e(49) - modifier));
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_RSQUARE));
            return tree;
        }
        if (current.getId() == 46) {
            /* $e = :identifier <=> :dot :identifier -> MemberAccess( lhs=$0, rhs=$2 ) */
            ctx.rule = rules.get(135);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("lhs", 0);
            parameters.put("rhs", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("MemberAccess", parameters));
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_DOT));
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_IDENTIFIER));
            return tree;
        }
        return tree;
    }
    public ParseTree parse__gen11(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen11(ctx);
    }
    private static ParseTree parse__gen11(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[0][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(56, "_gen11"));
        ctx.nonterminal = "_gen11";
        tree.setList(true);
        if ( current != null &&
             !nonterminal_first.get(56).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(56).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 36) {
            /* $_gen11 = $cmd_param_kv $_gen12 */
            ctx.rule = rules.get(36);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_cmd_param_kv(ctx);
            tree.add(subtree);
            subtree = parse__gen12(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse_call_input(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_call_input(ctx);
    }
    private static ParseTree parse_call_input(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[1][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(57, "call_input"));
        ctx.nonterminal = "call_input";
        tree.setList(false);
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "call_input",
                nonterminal_first.get(57),
                nonterminal_rules.get(57)
            ));
        }
        if (rule == 91) {
            /* $call_input = :input :colon $_gen25 -> Inputs( map=$2 ) */
            ctx.rule = rules.get(91);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("map", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("Inputs", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_INPUT);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_COLON);
            tree.add(next);
            subtree = parse__gen25(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "call_input",
            current,
            nonterminal_first.get(57),
            rules.get(91)
        ));
    }
    public ParseTree parse_parameter_meta(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_parameter_meta(ctx);
    }
    private static ParseTree parse_parameter_meta(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[2][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(58, "parameter_meta"));
        ctx.nonterminal = "parameter_meta";
        tree.setList(false);
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "parameter_meta",
                nonterminal_first.get(58),
                nonterminal_rules.get(58)
            ));
        }
        if (rule == 49) {
            /* $parameter_meta = :parameter_meta $map -> ParameterMeta( map=$1 ) */
            ctx.rule = rules.get(49);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("map", 1);
            tree.setAstTransformation(new AstTransformNodeCreator("ParameterMeta", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_PARAMETER_META);
            tree.add(next);
            subtree = parse_map(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "parameter_meta",
            current,
            nonterminal_first.get(58),
            rules.get(49)
        ));
    }
    public ParseTree parse_sections(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_sections(ctx);
    }
    private static ParseTree parse_sections(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[3][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(59, "sections"));
        ctx.nonterminal = "sections";
        tree.setList(false);
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "sections",
                nonterminal_first.get(59),
                nonterminal_rules.get(59)
            ));
        }
        if (rule == 24) {
            /* $sections = $command */
            ctx.rule = rules.get(24);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_command(ctx);
            tree.add(subtree);
            return tree;
        }
        else if (rule == 25) {
            /* $sections = $outputs */
            ctx.rule = rules.get(25);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_outputs(ctx);
            tree.add(subtree);
            return tree;
        }
        else if (rule == 26) {
            /* $sections = $runtime */
            ctx.rule = rules.get(26);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_runtime(ctx);
            tree.add(subtree);
            return tree;
        }
        else if (rule == 27) {
            /* $sections = $parameter_meta */
            ctx.rule = rules.get(27);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_parameter_meta(ctx);
            tree.add(subtree);
            return tree;
        }
        else if (rule == 28) {
            /* $sections = $meta */
            ctx.rule = rules.get(28);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_meta(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "sections",
            current,
            nonterminal_first.get(59),
            rules.get(28)
        ));
    }
    public ParseTree parse__gen13(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen13(ctx);
    }
    private static ParseTree parse__gen13(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[4][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(60, "_gen13"));
        ctx.nonterminal = "_gen13";
        tree.setList(true);
        if ( current != null &&
             !nonterminal_first.get(60).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(60).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 42) {
            /* $_gen13 = $output_kv $_gen14 */
            ctx.rule = rules.get(42);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_output_kv(ctx);
            tree.add(subtree);
            subtree = parse__gen14(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse__gen22(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen22(ctx);
    }
    private static ParseTree parse__gen22(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[5][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(61, "_gen22"));
        ctx.nonterminal = "_gen22";
        tree.setList(false);
        if ( current != null &&
             !nonterminal_first.get(61).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(61).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 79) {
            /* $_gen22 = $call_body */
            ctx.rule = rules.get(79);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_call_body(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse__gen6(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen6(ctx);
    }
    private static ParseTree parse__gen6(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[6][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(62, "_gen6"));
        ctx.nonterminal = "_gen6";
        tree.setList(true);
        if ( current != null &&
             !nonterminal_first.get(62).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(62).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 16) {
            /* $_gen6 = $declaration $_gen6 */
            ctx.rule = rules.get(16);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_declaration(ctx);
            tree.add(subtree);
            subtree = parse__gen6(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse__gen8(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen8(ctx);
    }
    private static ParseTree parse__gen8(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[7][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(63, "_gen8"));
        ctx.nonterminal = "_gen8";
        tree.setList(true);
        if ( current != null &&
             !nonterminal_first.get(63).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(63).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 20) {
            /* $_gen8 = $sections $_gen8 */
            ctx.rule = rules.get(20);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_sections(ctx);
            tree.add(subtree);
            subtree = parse__gen8(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse_map(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_map(ctx);
    }
    private static ParseTree parse_map(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[8][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(64, "map"));
        ctx.nonterminal = "map";
        tree.setList(false);
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "map",
                nonterminal_first.get(64),
                nonterminal_rules.get(64)
            ));
        }
        if (rule == 55) {
            /* $map = :lbrace $_gen15 :rbrace -> $1 */
            ctx.rule = rules.get(55);
            tree.setAstTransformation(new AstTransformSubstitution(1));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_LBRACE);
            tree.add(next);
            subtree = parse__gen15(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RBRACE);
            tree.add(next);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "map",
            current,
            nonterminal_first.get(64),
            rules.get(55)
        ));
    }
    public ParseTree parse_setter(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_setter(ctx);
    }
    private static ParseTree parse_setter(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[9][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(65, "setter"));
        ctx.nonterminal = "setter";
        tree.setList(false);
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "setter",
                nonterminal_first.get(65),
                nonterminal_rules.get(65)
            ));
        }
        if (rule == 62) {
            /* $setter = :equal $e -> $1 */
            ctx.rule = rules.get(62);
            tree.setAstTransformation(new AstTransformSubstitution(1));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_EQUAL);
            tree.add(next);
            subtree = parse_e(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "setter",
            current,
            nonterminal_first.get(65),
            rules.get(62)
        ));
    }
    public ParseTree parse_import_namespace(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_import_namespace(ctx);
    }
    private static ParseTree parse_import_namespace(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[10][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(66, "import_namespace"));
        ctx.nonterminal = "import_namespace";
        tree.setList(false);
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "import_namespace",
                nonterminal_first.get(66),
                nonterminal_rules.get(66)
            ));
        }
        if (rule == 14) {
            /* $import_namespace = :as :identifier -> $1 */
            ctx.rule = rules.get(14);
            tree.setAstTransformation(new AstTransformSubstitution(1));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_AS);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_IDENTIFIER);
            tree.add(next);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "import_namespace",
            current,
            nonterminal_first.get(66),
            rules.get(14)
        ));
    }
    public ParseTree parse_cmd_param(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_cmd_param(ctx);
    }
    private static ParseTree parse_cmd_param(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[11][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(67, "cmd_param"));
        ctx.nonterminal = "cmd_param";
        tree.setList(false);
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "cmd_param",
                nonterminal_first.get(67),
                nonterminal_rules.get(67)
            ));
        }
        if (rule == 40) {
            /* $cmd_param = :cmd_param_start $_gen11 $e :cmd_param_end -> CommandParameter( attributes=$1, expr=$2 ) */
            ctx.rule = rules.get(40);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("attributes", 1);
            parameters.put("expr", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("CommandParameter", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_CMD_PARAM_START);
            tree.add(next);
            subtree = parse__gen11(ctx);
            tree.add(subtree);
            subtree = parse_e(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_CMD_PARAM_END);
            tree.add(next);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "cmd_param",
            current,
            nonterminal_first.get(67),
            rules.get(40)
        ));
    }
    public ParseTree parse_mapping(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_mapping(ctx);
    }
    private static ParseTree parse_mapping(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[12][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(68, "mapping"));
        ctx.nonterminal = "mapping";
        tree.setList(false);
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "mapping",
                nonterminal_first.get(68),
                nonterminal_rules.get(68)
            ));
        }
        if (rule == 92) {
            /* $mapping = :identifier :equal $e -> IOMapping( key=$0, value=$2 ) */
            ctx.rule = rules.get(92);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("key", 0);
            parameters.put("value", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("IOMapping", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_IDENTIFIER);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_EQUAL);
            tree.add(next);
            subtree = parse_e(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "mapping",
            current,
            nonterminal_first.get(68),
            rules.get(92)
        ));
    }
    public ParseTree parse__gen28(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen28(ctx);
    }
    private static ParseTree parse__gen28(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[13][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(69, "_gen28"));
        ctx.nonterminal = "_gen28";
        tree.setList(true);
        if ( current != null &&
             !nonterminal_first.get(69).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(69).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 95) {
            /* $_gen28 = :comma $wf_output $_gen28 */
            ctx.rule = rules.get(95);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_COMMA);
            tree.add(next);
            tree.setListSeparator(next);
            subtree = parse_wf_output(ctx);
            tree.add(subtree);
            subtree = parse__gen28(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse__gen20(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen20(ctx);
    }
    private static ParseTree parse__gen20(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[14][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(70, "_gen20"));
        ctx.nonterminal = "_gen20";
        tree.setList(true);
        if ( current != null &&
             !nonterminal_first.get(70).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(70).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 67) {
            /* $_gen20 = $wf_body_element $_gen20 */
            ctx.rule = rules.get(67);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_wf_body_element(ctx);
            tree.add(subtree);
            subtree = parse__gen20(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse__gen7(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen7(ctx);
    }
    private static ParseTree parse__gen7(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[15][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(71, "_gen7"));
        ctx.nonterminal = "_gen7";
        tree.setList(true);
        if ( current != null &&
             !nonterminal_first.get(71).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(71).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 19) {
            /* $_gen7 = $sections $_gen8 */
            ctx.rule = rules.get(19);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_sections(ctx);
            tree.add(subtree);
            subtree = parse__gen8(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse_postfix_quantifier(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_postfix_quantifier(ctx);
    }
    private static ParseTree parse_postfix_quantifier(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[16][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(72, "postfix_quantifier"));
        ctx.nonterminal = "postfix_quantifier";
        tree.setList(false);
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "postfix_quantifier",
                nonterminal_first.get(72),
                nonterminal_rules.get(72)
            ));
        }
        if (rule == 63) {
            /* $postfix_quantifier = :qmark */
            ctx.rule = rules.get(63);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_QMARK);
            tree.add(next);
            return tree;
        }
        else if (rule == 64) {
            /* $postfix_quantifier = :plus */
            ctx.rule = rules.get(64);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_PLUS);
            tree.add(next);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "postfix_quantifier",
            current,
            nonterminal_first.get(72),
            rules.get(64)
        ));
    }
    public ParseTree parse__gen35(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen35(ctx);
    }
    private static ParseTree parse__gen35(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[17][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(73, "_gen35"));
        ctx.nonterminal = "_gen35";
        tree.setList(true);
        if ( current != null &&
             !nonterminal_first.get(73).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(73).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 137) {
            /* $_gen35 = :comma $object_kv $_gen35 */
            ctx.rule = rules.get(137);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_COMMA);
            tree.add(next);
            tree.setListSeparator(next);
            subtree = parse_object_kv(ctx);
            tree.add(subtree);
            subtree = parse__gen35(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse__gen16(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen16(ctx);
    }
    private static ParseTree parse__gen16(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[18][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(74, "_gen16"));
        ctx.nonterminal = "_gen16";
        tree.setList(true);
        if ( current != null &&
             !nonterminal_first.get(74).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(74).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 52) {
            /* $_gen16 = $kv $_gen16 */
            ctx.rule = rules.get(52);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_kv(ctx);
            tree.add(subtree);
            subtree = parse__gen16(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse_while_loop(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_while_loop(ctx);
    }
    private static ParseTree parse_while_loop(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[19][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(75, "while_loop"));
        ctx.nonterminal = "while_loop";
        tree.setList(false);
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "while_loop",
                nonterminal_first.get(75),
                nonterminal_rules.get(75)
            ));
        }
        if (rule == 103) {
            /* $while_loop = :while :lparen $e :rparen :lbrace $_gen19 :rbrace -> WhileLoop( expression=$2, body=$5 ) */
            ctx.rule = rules.get(103);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("expression", 2);
            parameters.put("body", 5);
            tree.setAstTransformation(new AstTransformNodeCreator("WhileLoop", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_WHILE);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_LPAREN);
            tree.add(next);
            subtree = parse_e(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RPAREN);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_LBRACE);
            tree.add(next);
            subtree = parse__gen19(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RBRACE);
            tree.add(next);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "while_loop",
            current,
            nonterminal_first.get(75),
            rules.get(103)
        ));
    }
    public ParseTree parse__gen3(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen3(ctx);
    }
    private static ParseTree parse__gen3(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[21][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(77, "_gen3"));
        ctx.nonterminal = "_gen3";
        tree.setList(true);
        if ( current != null &&
             !nonterminal_first.get(77).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(77).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 5) {
            /* $_gen3 = $workflow_or_task $_gen3 */
            ctx.rule = rules.get(5);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_workflow_or_task(ctx);
            tree.add(subtree);
            subtree = parse__gen3(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse__gen30(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen30(ctx);
    }
    private static ParseTree parse__gen30(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[22][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(78, "_gen30"));
        ctx.nonterminal = "_gen30";
        tree.setList(true);
        if ( current != null &&
             !nonterminal_first.get(78).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(78).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 107) {
            /* $_gen30 = $type_e $_gen31 */
            ctx.rule = rules.get(107);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_type_e(ctx);
            tree.add(subtree);
            subtree = parse__gen31(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse__gen36(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen36(ctx);
    }
    private static ParseTree parse__gen36(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[23][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(79, "_gen36"));
        ctx.nonterminal = "_gen36";
        tree.setList(true);
        if ( current != null &&
             !nonterminal_first.get(79).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(79).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 142) {
            /* $_gen36 = $map_kv $_gen37 */
            ctx.rule = rules.get(142);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_map_kv(ctx);
            tree.add(subtree);
            subtree = parse__gen37(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse_meta(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_meta(ctx);
    }
    private static ParseTree parse_meta(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[24][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(80, "meta"));
        ctx.nonterminal = "meta";
        tree.setList(false);
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "meta",
                nonterminal_first.get(80),
                nonterminal_rules.get(80)
            ));
        }
        if (rule == 50) {
            /* $meta = :meta $map -> Meta( map=$1 ) */
            ctx.rule = rules.get(50);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("map", 1);
            tree.setAstTransformation(new AstTransformNodeCreator("Meta", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_META);
            tree.add(next);
            subtree = parse_map(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "meta",
            current,
            nonterminal_first.get(80),
            rules.get(50)
        ));
    }
    public ParseTree parse__gen19(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen19(ctx);
    }
    private static ParseTree parse__gen19(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[25][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(81, "_gen19"));
        ctx.nonterminal = "_gen19";
        tree.setList(true);
        if ( current != null &&
             !nonterminal_first.get(81).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(81).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 66) {
            /* $_gen19 = $wf_body_element $_gen20 */
            ctx.rule = rules.get(66);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_wf_body_element(ctx);
            tree.add(subtree);
            subtree = parse__gen20(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse_output_kv(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_output_kv(ctx);
    }
    private static ParseTree parse_output_kv(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[26][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(82, "output_kv"));
        ctx.nonterminal = "output_kv";
        tree.setList(false);
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "output_kv",
                nonterminal_first.get(82),
                nonterminal_rules.get(82)
            ));
        }
        if (rule == 47) {
            /* $output_kv = $type_e :identifier :equal $e -> Output( type=$0, var=$1, expression=$3 ) */
            ctx.rule = rules.get(47);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("type", 0);
            parameters.put("var", 1);
            parameters.put("expression", 3);
            tree.setAstTransformation(new AstTransformNodeCreator("Output", parameters));
            subtree = parse_type_e(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_IDENTIFIER);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_EQUAL);
            tree.add(next);
            subtree = parse_e(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "output_kv",
            current,
            nonterminal_first.get(82),
            rules.get(47)
        ));
    }
    public ParseTree parse__gen25(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen25(ctx);
    }
    private static ParseTree parse__gen25(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[28][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(84, "_gen25"));
        ctx.nonterminal = "_gen25";
        tree.setList(true);
        if ( current != null &&
             !nonterminal_first.get(84).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(84).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 87) {
            /* $_gen25 = $mapping $_gen26 */
            ctx.rule = rules.get(87);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_mapping(ctx);
            tree.add(subtree);
            subtree = parse__gen26(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse_object_kv(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_object_kv(ctx);
    }
    private static ParseTree parse_object_kv(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[29][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(85, "object_kv"));
        ctx.nonterminal = "object_kv";
        tree.setList(false);
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "object_kv",
                nonterminal_first.get(85),
                nonterminal_rules.get(85)
            ));
        }
        if (rule == 106) {
            /* $object_kv = :identifier :colon $e -> ObjectKV( key=$0, value=$2 ) */
            ctx.rule = rules.get(106);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("key", 0);
            parameters.put("value", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("ObjectKV", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_IDENTIFIER);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_COLON);
            tree.add(next);
            subtree = parse_e(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "object_kv",
            current,
            nonterminal_first.get(85),
            rules.get(106)
        ));
    }
    public ParseTree parse__gen31(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen31(ctx);
    }
    private static ParseTree parse__gen31(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[30][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(86, "_gen31"));
        ctx.nonterminal = "_gen31";
        tree.setList(true);
        if ( current != null &&
             !nonterminal_first.get(86).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(86).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 108) {
            /* $_gen31 = :comma $type_e $_gen31 */
            ctx.rule = rules.get(108);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_COMMA);
            tree.add(next);
            tree.setListSeparator(next);
            subtree = parse_type_e(ctx);
            tree.add(subtree);
            subtree = parse__gen31(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse__gen0(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen0(ctx);
    }
    private static ParseTree parse__gen0(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[31][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(87, "_gen0"));
        ctx.nonterminal = "_gen0";
        tree.setList(true);
        if ( current != null &&
             !nonterminal_first.get(87).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(87).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 0) {
            /* $_gen0 = $import $_gen1 */
            ctx.rule = rules.get(0);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_import(ctx);
            tree.add(subtree);
            subtree = parse__gen1(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse_kv(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_kv(ctx);
    }
    private static ParseTree parse_kv(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[32][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(88, "kv"));
        ctx.nonterminal = "kv";
        tree.setList(false);
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "kv",
                nonterminal_first.get(88),
                nonterminal_rules.get(88)
            ));
        }
        if (rule == 56) {
            /* $kv = :identifier :colon $e -> RuntimeAttribute( key=$0, value=$2 ) */
            ctx.rule = rules.get(56);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("key", 0);
            parameters.put("value", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("RuntimeAttribute", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_IDENTIFIER);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_COLON);
            tree.add(next);
            subtree = parse_e(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "kv",
            current,
            nonterminal_first.get(88),
            rules.get(56)
        ));
    }
    public ParseTree parse_workflow(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_workflow(ctx);
    }
    private static ParseTree parse_workflow(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[33][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(89, "workflow"));
        ctx.nonterminal = "workflow";
        tree.setList(false);
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "workflow",
                nonterminal_first.get(89),
                nonterminal_rules.get(89)
            ));
        }
        if (rule == 70) {
            /* $workflow = :workflow :identifier :lbrace $_gen19 :rbrace -> Workflow( name=$1, body=$3 ) */
            ctx.rule = rules.get(70);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("name", 1);
            parameters.put("body", 3);
            tree.setAstTransformation(new AstTransformNodeCreator("Workflow", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_WORKFLOW);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_IDENTIFIER);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_LBRACE);
            tree.add(next);
            subtree = parse__gen19(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RBRACE);
            tree.add(next);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "workflow",
            current,
            nonterminal_first.get(89),
            rules.get(70)
        ));
    }
    public ParseTree parse__gen5(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen5(ctx);
    }
    private static ParseTree parse__gen5(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[34][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(90, "_gen5"));
        ctx.nonterminal = "_gen5";
        tree.setList(true);
        if ( current != null &&
             !nonterminal_first.get(90).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(90).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 15) {
            /* $_gen5 = $declaration $_gen6 */
            ctx.rule = rules.get(15);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_declaration(ctx);
            tree.add(subtree);
            subtree = parse__gen6(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse_command(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_command(ctx);
    }
    private static ParseTree parse_command(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[35][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(91, "command"));
        ctx.nonterminal = "command";
        tree.setList(false);
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "command",
                nonterminal_first.get(91),
                nonterminal_rules.get(91)
            ));
        }
        if (rule == 33) {
            /* $command = :raw_command :raw_cmd_start $_gen9 :raw_cmd_end -> RawCommand( parts=$2 ) */
            ctx.rule = rules.get(33);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("parts", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("RawCommand", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RAW_COMMAND);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RAW_CMD_START);
            tree.add(next);
            subtree = parse__gen9(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RAW_CMD_END);
            tree.add(next);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "command",
            current,
            nonterminal_first.get(91),
            rules.get(33)
        ));
    }
    public ParseTree parse_import(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_import(ctx);
    }
    private static ParseTree parse_import(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[36][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(92, "import"));
        ctx.nonterminal = "import";
        tree.setList(false);
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "import",
                nonterminal_first.get(92),
                nonterminal_rules.get(92)
            ));
        }
        if (rule == 13) {
            /* $import = :import :string $_gen4 -> Import( uri=$1, namespace=$2 ) */
            ctx.rule = rules.get(13);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("uri", 1);
            parameters.put("namespace", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("Import", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_IMPORT);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_STRING);
            tree.add(next);
            subtree = parse__gen4(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "import",
            current,
            nonterminal_first.get(92),
            rules.get(13)
        ));
    }
    public ParseTree parse_declaration(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_declaration(ctx);
    }
    private static ParseTree parse_declaration(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[37][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(93, "declaration"));
        ctx.nonterminal = "declaration";
        tree.setList(false);
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "declaration",
                nonterminal_first.get(93),
                nonterminal_rules.get(93)
            ));
        }
        if (rule == 61) {
            /* $declaration = $type_e $_gen17 :identifier $_gen18 -> Declaration( type=$0, postfix=$1, name=$2, expression=$3 ) */
            ctx.rule = rules.get(61);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("type", 0);
            parameters.put("postfix", 1);
            parameters.put("name", 2);
            parameters.put("expression", 3);
            tree.setAstTransformation(new AstTransformNodeCreator("Declaration", parameters));
            subtree = parse_type_e(ctx);
            tree.add(subtree);
            subtree = parse__gen17(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_IDENTIFIER);
            tree.add(next);
            subtree = parse__gen18(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "declaration",
            current,
            nonterminal_first.get(93),
            rules.get(61)
        ));
    }
    public ParseTree parse_outputs(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_outputs(ctx);
    }
    private static ParseTree parse_outputs(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[38][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(94, "outputs"));
        ctx.nonterminal = "outputs";
        tree.setList(false);
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "outputs",
                nonterminal_first.get(94),
                nonterminal_rules.get(94)
            ));
        }
        if (rule == 46) {
            /* $outputs = :output :lbrace $_gen13 :rbrace -> Outputs( attributes=$2 ) */
            ctx.rule = rules.get(46);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("attributes", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("Outputs", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_OUTPUT);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_LBRACE);
            tree.add(next);
            subtree = parse__gen13(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RBRACE);
            tree.add(next);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "outputs",
            current,
            nonterminal_first.get(94),
            rules.get(46)
        ));
    }
    public ParseTree parse__gen29(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen29(ctx);
    }
    private static ParseTree parse__gen29(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[39][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(95, "_gen29"));
        ctx.nonterminal = "_gen29";
        tree.setList(false);
        if ( current != null &&
             !nonterminal_first.get(95).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(95).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 99) {
            /* $_gen29 = $wf_output_wildcard */
            ctx.rule = rules.get(99);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_wf_output_wildcard(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse_wf_output(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_wf_output(ctx);
    }
    private static ParseTree parse_wf_output(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[40][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(96, "wf_output"));
        ctx.nonterminal = "wf_output";
        tree.setList(false);
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "wf_output",
                nonterminal_first.get(96),
                nonterminal_rules.get(96)
            ));
        }
        if (rule == 101) {
            /* $wf_output = :fqn $_gen29 -> WorkflowOutput( fqn=$0, wildcard=$1 ) */
            ctx.rule = rules.get(101);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("fqn", 0);
            parameters.put("wildcard", 1);
            tree.setAstTransformation(new AstTransformNodeCreator("WorkflowOutput", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_FQN);
            tree.add(next);
            subtree = parse__gen29(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "wf_output",
            current,
            nonterminal_first.get(96),
            rules.get(101)
        ));
    }
    public ParseTree parse__gen17(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen17(ctx);
    }
    private static ParseTree parse__gen17(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[41][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(97, "_gen17"));
        ctx.nonterminal = "_gen17";
        tree.setList(false);
        if ( current != null &&
             !nonterminal_first.get(97).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(97).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 57) {
            /* $_gen17 = $postfix_quantifier */
            ctx.rule = rules.get(57);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_postfix_quantifier(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse__gen33(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen33(ctx);
    }
    private static ParseTree parse__gen33(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[42][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(98, "_gen33"));
        ctx.nonterminal = "_gen33";
        tree.setList(true);
        if ( current != null &&
             !nonterminal_first.get(98).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(98).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 130) {
            /* $_gen33 = :comma $e $_gen33 */
            ctx.rule = rules.get(130);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_COMMA);
            tree.add(next);
            tree.setListSeparator(next);
            subtree = parse_e(ctx);
            tree.add(subtree);
            subtree = parse__gen33(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse_call(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_call(ctx);
    }
    private static ParseTree parse_call(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[43][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(99, "call"));
        ctx.nonterminal = "call";
        tree.setList(false);
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "call",
                nonterminal_first.get(99),
                nonterminal_rules.get(99)
            ));
        }
        if (rule == 81) {
            /* $call = :call :fqn $_gen21 $_gen22 -> Call( task=$1, alias=$2, body=$3 ) */
            ctx.rule = rules.get(81);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("task", 1);
            parameters.put("alias", 2);
            parameters.put("body", 3);
            tree.setAstTransformation(new AstTransformNodeCreator("Call", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_CALL);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_FQN);
            tree.add(next);
            subtree = parse__gen21(ctx);
            tree.add(subtree);
            subtree = parse__gen22(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "call",
            current,
            nonterminal_first.get(99),
            rules.get(81)
        ));
    }
    public ParseTree parse__gen26(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen26(ctx);
    }
    private static ParseTree parse__gen26(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[44][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(100, "_gen26"));
        ctx.nonterminal = "_gen26";
        tree.setList(true);
        if ( current != null &&
             !nonterminal_first.get(100).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(100).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 88) {
            /* $_gen26 = :comma $mapping $_gen26 */
            ctx.rule = rules.get(88);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_COMMA);
            tree.add(next);
            tree.setListSeparator(next);
            subtree = parse_mapping(ctx);
            tree.add(subtree);
            subtree = parse__gen26(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse__gen37(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen37(ctx);
    }
    private static ParseTree parse__gen37(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[45][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(101, "_gen37"));
        ctx.nonterminal = "_gen37";
        tree.setList(true);
        if ( current != null &&
             !nonterminal_first.get(101).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(101).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 143) {
            /* $_gen37 = :comma $map_kv $_gen37 */
            ctx.rule = rules.get(143);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_COMMA);
            tree.add(next);
            tree.setListSeparator(next);
            subtree = parse_map_kv(ctx);
            tree.add(subtree);
            subtree = parse__gen37(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse__gen14(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen14(ctx);
    }
    private static ParseTree parse__gen14(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[46][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(102, "_gen14"));
        ctx.nonterminal = "_gen14";
        tree.setList(true);
        if ( current != null &&
             !nonterminal_first.get(102).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(102).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 43) {
            /* $_gen14 = $output_kv $_gen14 */
            ctx.rule = rules.get(43);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_output_kv(ctx);
            tree.add(subtree);
            subtree = parse__gen14(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse__gen9(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen9(ctx);
    }
    private static ParseTree parse__gen9(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[47][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(103, "_gen9"));
        ctx.nonterminal = "_gen9";
        tree.setList(true);
        if ( current != null &&
             !nonterminal_first.get(103).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(103).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 29) {
            /* $_gen9 = $command_part $_gen10 */
            ctx.rule = rules.get(29);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_command_part(ctx);
            tree.add(subtree);
            subtree = parse__gen10(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse__gen12(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen12(ctx);
    }
    private static ParseTree parse__gen12(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[48][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(104, "_gen12"));
        ctx.nonterminal = "_gen12";
        tree.setList(true);
        if ( current != null &&
             !nonterminal_first.get(104).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(104).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 37) {
            /* $_gen12 = $cmd_param_kv $_gen12 */
            ctx.rule = rules.get(37);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_cmd_param_kv(ctx);
            tree.add(subtree);
            subtree = parse__gen12(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse_wf_output_wildcard(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_wf_output_wildcard(ctx);
    }
    private static ParseTree parse_wf_output_wildcard(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[49][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(105, "wf_output_wildcard"));
        ctx.nonterminal = "wf_output_wildcard";
        tree.setList(false);
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "wf_output_wildcard",
                nonterminal_first.get(105),
                nonterminal_rules.get(105)
            ));
        }
        if (rule == 102) {
            /* $wf_output_wildcard = :dot :asterisk -> $1 */
            ctx.rule = rules.get(102);
            tree.setAstTransformation(new AstTransformSubstitution(1));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_DOT);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_ASTERISK);
            tree.add(next);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "wf_output_wildcard",
            current,
            nonterminal_first.get(105),
            rules.get(102)
        ));
    }
    public ParseTree parse_scatter(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_scatter(ctx);
    }
    private static ParseTree parse_scatter(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[50][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(106, "scatter"));
        ctx.nonterminal = "scatter";
        tree.setList(false);
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "scatter",
                nonterminal_first.get(106),
                nonterminal_rules.get(106)
            ));
        }
        if (rule == 105) {
            /* $scatter = :scatter :lparen :identifier :in $e :rparen :lbrace $_gen19 :rbrace -> Scatter( item=$2, collection=$4, body=$7 ) */
            ctx.rule = rules.get(105);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("item", 2);
            parameters.put("collection", 4);
            parameters.put("body", 7);
            tree.setAstTransformation(new AstTransformNodeCreator("Scatter", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_SCATTER);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_LPAREN);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_IDENTIFIER);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_IN);
            tree.add(next);
            subtree = parse_e(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RPAREN);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_LBRACE);
            tree.add(next);
            subtree = parse__gen19(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RBRACE);
            tree.add(next);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "scatter",
            current,
            nonterminal_first.get(106),
            rules.get(105)
        ));
    }
    public ParseTree parse__gen2(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen2(ctx);
    }
    private static ParseTree parse__gen2(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[51][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(107, "_gen2"));
        ctx.nonterminal = "_gen2";
        tree.setList(true);
        if ( current != null &&
             !nonterminal_first.get(107).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(107).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 4) {
            /* $_gen2 = $workflow_or_task $_gen3 */
            ctx.rule = rules.get(4);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_workflow_or_task(ctx);
            tree.add(subtree);
            subtree = parse__gen3(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse__gen1(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen1(ctx);
    }
    private static ParseTree parse__gen1(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[52][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(108, "_gen1"));
        ctx.nonterminal = "_gen1";
        tree.setList(true);
        if ( current != null &&
             !nonterminal_first.get(108).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(108).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 1) {
            /* $_gen1 = $import $_gen1 */
            ctx.rule = rules.get(1);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_import(ctx);
            tree.add(subtree);
            subtree = parse__gen1(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse__gen10(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen10(ctx);
    }
    private static ParseTree parse__gen10(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[53][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(109, "_gen10"));
        ctx.nonterminal = "_gen10";
        tree.setList(true);
        if ( current != null &&
             !nonterminal_first.get(109).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(109).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 30) {
            /* $_gen10 = $command_part $_gen10 */
            ctx.rule = rules.get(30);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_command_part(ctx);
            tree.add(subtree);
            subtree = parse__gen10(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse_wf_body_element(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_wf_body_element(ctx);
    }
    private static ParseTree parse_wf_body_element(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[54][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(110, "wf_body_element"));
        ctx.nonterminal = "wf_body_element";
        tree.setList(false);
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "wf_body_element",
                nonterminal_first.get(110),
                nonterminal_rules.get(110)
            ));
        }
        if (rule == 71) {
            /* $wf_body_element = $call */
            ctx.rule = rules.get(71);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_call(ctx);
            tree.add(subtree);
            return tree;
        }
        else if (rule == 72) {
            /* $wf_body_element = $declaration */
            ctx.rule = rules.get(72);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_declaration(ctx);
            tree.add(subtree);
            return tree;
        }
        else if (rule == 73) {
            /* $wf_body_element = $while_loop */
            ctx.rule = rules.get(73);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_while_loop(ctx);
            tree.add(subtree);
            return tree;
        }
        else if (rule == 74) {
            /* $wf_body_element = $if_stmt */
            ctx.rule = rules.get(74);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_if_stmt(ctx);
            tree.add(subtree);
            return tree;
        }
        else if (rule == 75) {
            /* $wf_body_element = $scatter */
            ctx.rule = rules.get(75);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_scatter(ctx);
            tree.add(subtree);
            return tree;
        }
        else if (rule == 76) {
            /* $wf_body_element = $wf_outputs */
            ctx.rule = rules.get(76);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_wf_outputs(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "wf_body_element",
            current,
            nonterminal_first.get(110),
            rules.get(76)
        ));
    }
    public ParseTree parse__gen24(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen24(ctx);
    }
    private static ParseTree parse__gen24(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[55][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(111, "_gen24"));
        ctx.nonterminal = "_gen24";
        tree.setList(true);
        if ( current != null &&
             !nonterminal_first.get(111).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(111).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 83) {
            /* $_gen24 = $call_input $_gen24 */
            ctx.rule = rules.get(83);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_call_input(ctx);
            tree.add(subtree);
            subtree = parse__gen24(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse__gen32(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen32(ctx);
    }
    private static ParseTree parse__gen32(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[56][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(112, "_gen32"));
        ctx.nonterminal = "_gen32";
        tree.setList(true);
        if ( current != null &&
             !nonterminal_first.get(112).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(112).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 129) {
            /* $_gen32 = $e $_gen33 */
            ctx.rule = rules.get(129);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_e(ctx);
            tree.add(subtree);
            subtree = parse__gen33(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse__gen21(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen21(ctx);
    }
    private static ParseTree parse__gen21(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[57][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(113, "_gen21"));
        ctx.nonterminal = "_gen21";
        tree.setList(false);
        if ( current != null &&
             !nonterminal_first.get(113).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(113).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 77) {
            /* $_gen21 = $alias */
            ctx.rule = rules.get(77);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_alias(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse__gen15(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen15(ctx);
    }
    private static ParseTree parse__gen15(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[58][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(114, "_gen15"));
        ctx.nonterminal = "_gen15";
        tree.setList(true);
        if ( current != null &&
             !nonterminal_first.get(114).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(114).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 51) {
            /* $_gen15 = $kv $_gen16 */
            ctx.rule = rules.get(51);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_kv(ctx);
            tree.add(subtree);
            subtree = parse__gen16(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse_workflow_or_task(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_workflow_or_task(ctx);
    }
    private static ParseTree parse_workflow_or_task(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[59][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(115, "workflow_or_task"));
        ctx.nonterminal = "workflow_or_task";
        tree.setList(false);
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "workflow_or_task",
                nonterminal_first.get(115),
                nonterminal_rules.get(115)
            ));
        }
        if (rule == 9) {
            /* $workflow_or_task = $workflow */
            ctx.rule = rules.get(9);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_workflow(ctx);
            tree.add(subtree);
            return tree;
        }
        else if (rule == 10) {
            /* $workflow_or_task = $task */
            ctx.rule = rules.get(10);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_task(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "workflow_or_task",
            current,
            nonterminal_first.get(115),
            rules.get(10)
        ));
    }
    public ParseTree parse_call_body(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_call_body(ctx);
    }
    private static ParseTree parse_call_body(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[60][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(116, "call_body"));
        ctx.nonterminal = "call_body";
        tree.setList(false);
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "call_body",
                nonterminal_first.get(116),
                nonterminal_rules.get(116)
            ));
        }
        if (rule == 86) {
            /* $call_body = :lbrace $_gen5 $_gen23 :rbrace -> CallBody( declarations=$1, io=$2 ) */
            ctx.rule = rules.get(86);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("declarations", 1);
            parameters.put("io", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("CallBody", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_LBRACE);
            tree.add(next);
            subtree = parse__gen5(ctx);
            tree.add(subtree);
            subtree = parse__gen23(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RBRACE);
            tree.add(next);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "call_body",
            current,
            nonterminal_first.get(116),
            rules.get(86)
        ));
    }
    public ParseTree parse_command_part(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_command_part(ctx);
    }
    private static ParseTree parse_command_part(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[61][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(117, "command_part"));
        ctx.nonterminal = "command_part";
        tree.setList(false);
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "command_part",
                nonterminal_first.get(117),
                nonterminal_rules.get(117)
            ));
        }
        if (rule == 34) {
            /* $command_part = :cmd_part */
            ctx.rule = rules.get(34);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_CMD_PART);
            tree.add(next);
            return tree;
        }
        else if (rule == 35) {
            /* $command_part = $cmd_param */
            ctx.rule = rules.get(35);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_cmd_param(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "command_part",
            current,
            nonterminal_first.get(117),
            rules.get(35)
        ));
    }
    public ParseTree parse_document(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_document(ctx);
    }
    private static ParseTree parse_document(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[62][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(118, "document"));
        ctx.nonterminal = "document";
        tree.setList(false);
        if ( current != null &&
             !nonterminal_first.get(118).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(118).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 8) {
            /* $document = $_gen0 $_gen2 -> Document( imports=$0, definitions=$1 ) */
            ctx.rule = rules.get(8);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("imports", 0);
            parameters.put("definitions", 1);
            tree.setAstTransformation(new AstTransformNodeCreator("Document", parameters));
            subtree = parse__gen0(ctx);
            tree.add(subtree);
            subtree = parse__gen2(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse_runtime(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_runtime(ctx);
    }
    private static ParseTree parse_runtime(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[63][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(119, "runtime"));
        ctx.nonterminal = "runtime";
        tree.setList(false);
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "runtime",
                nonterminal_first.get(119),
                nonterminal_rules.get(119)
            ));
        }
        if (rule == 48) {
            /* $runtime = :runtime $map -> Runtime( map=$1 ) */
            ctx.rule = rules.get(48);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("map", 1);
            tree.setAstTransformation(new AstTransformNodeCreator("Runtime", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RUNTIME);
            tree.add(next);
            subtree = parse_map(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "runtime",
            current,
            nonterminal_first.get(119),
            rules.get(48)
        ));
    }
    public ParseTree parse__gen4(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen4(ctx);
    }
    private static ParseTree parse__gen4(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[64][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(120, "_gen4"));
        ctx.nonterminal = "_gen4";
        tree.setList(false);
        if ( current != null &&
             !nonterminal_first.get(120).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(120).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 11) {
            /* $_gen4 = $import_namespace */
            ctx.rule = rules.get(11);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_import_namespace(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse_map_kv(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_map_kv(ctx);
    }
    private static ParseTree parse_map_kv(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[65][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(121, "map_kv"));
        ctx.nonterminal = "map_kv";
        tree.setList(false);
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "map_kv",
                nonterminal_first.get(121),
                nonterminal_rules.get(121)
            ));
        }
        if (rule == 65) {
            /* $map_kv = $e :colon $e -> MapLiteralKv( key=$0, value=$2 ) */
            ctx.rule = rules.get(65);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("key", 0);
            parameters.put("value", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("MapLiteralKv", parameters));
            subtree = parse_e(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_COLON);
            tree.add(next);
            subtree = parse_e(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "map_kv",
            current,
            nonterminal_first.get(121),
            rules.get(65)
        ));
    }
    public ParseTree parse_cmd_param_kv(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_cmd_param_kv(ctx);
    }
    private static ParseTree parse_cmd_param_kv(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[66][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(122, "cmd_param_kv"));
        ctx.nonterminal = "cmd_param_kv";
        tree.setList(false);
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "cmd_param_kv",
                nonterminal_first.get(122),
                nonterminal_rules.get(122)
            ));
        }
        if (rule == 41) {
            /* $cmd_param_kv = :cmd_attr_hint :identifier :equal $e -> CommandParameterAttr( key=$1, value=$3 ) */
            ctx.rule = rules.get(41);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("key", 1);
            parameters.put("value", 3);
            tree.setAstTransformation(new AstTransformNodeCreator("CommandParameterAttr", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_CMD_ATTR_HINT);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_IDENTIFIER);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_EQUAL);
            tree.add(next);
            subtree = parse_e(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "cmd_param_kv",
            current,
            nonterminal_first.get(122),
            rules.get(41)
        ));
    }
    public ParseTree parse_wf_outputs(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_wf_outputs(ctx);
    }
    private static ParseTree parse_wf_outputs(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[67][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(123, "wf_outputs"));
        ctx.nonterminal = "wf_outputs";
        tree.setList(false);
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "wf_outputs",
                nonterminal_first.get(123),
                nonterminal_rules.get(123)
            ));
        }
        if (rule == 98) {
            /* $wf_outputs = :output :lbrace $_gen27 :rbrace -> WorkflowOutputs( outputs=$2 ) */
            ctx.rule = rules.get(98);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("outputs", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("WorkflowOutputs", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_OUTPUT);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_LBRACE);
            tree.add(next);
            subtree = parse__gen27(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RBRACE);
            tree.add(next);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "wf_outputs",
            current,
            nonterminal_first.get(123),
            rules.get(98)
        ));
    }
    public ParseTree parse_alias(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_alias(ctx);
    }
    private static ParseTree parse_alias(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[68][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(124, "alias"));
        ctx.nonterminal = "alias";
        tree.setList(false);
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "alias",
                nonterminal_first.get(124),
                nonterminal_rules.get(124)
            ));
        }
        if (rule == 93) {
            /* $alias = :as :identifier -> $1 */
            ctx.rule = rules.get(93);
            tree.setAstTransformation(new AstTransformSubstitution(1));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_AS);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_IDENTIFIER);
            tree.add(next);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "alias",
            current,
            nonterminal_first.get(124),
            rules.get(93)
        ));
    }
    public ParseTree parse_if_stmt(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_if_stmt(ctx);
    }
    private static ParseTree parse_if_stmt(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[69][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(125, "if_stmt"));
        ctx.nonterminal = "if_stmt";
        tree.setList(false);
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "if_stmt",
                nonterminal_first.get(125),
                nonterminal_rules.get(125)
            ));
        }
        if (rule == 104) {
            /* $if_stmt = :if :lparen $e :rparen :lbrace $_gen19 :rbrace -> If( expression=$2, body=$5 ) */
            ctx.rule = rules.get(104);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("expression", 2);
            parameters.put("body", 5);
            tree.setAstTransformation(new AstTransformNodeCreator("If", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_IF);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_LPAREN);
            tree.add(next);
            subtree = parse_e(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RPAREN);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_LBRACE);
            tree.add(next);
            subtree = parse__gen19(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RBRACE);
            tree.add(next);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "if_stmt",
            current,
            nonterminal_first.get(125),
            rules.get(104)
        ));
    }
    public ParseTree parse__gen23(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen23(ctx);
    }
    private static ParseTree parse__gen23(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[70][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(126, "_gen23"));
        ctx.nonterminal = "_gen23";
        tree.setList(true);
        if ( current != null &&
             !nonterminal_first.get(126).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(126).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 82) {
            /* $_gen23 = $call_input $_gen24 */
            ctx.rule = rules.get(82);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_call_input(ctx);
            tree.add(subtree);
            subtree = parse__gen24(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse_task(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_task(ctx);
    }
    private static ParseTree parse_task(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[71][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(127, "task"));
        ctx.nonterminal = "task";
        tree.setList(false);
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "task",
                nonterminal_first.get(127),
                nonterminal_rules.get(127)
            ));
        }
        if (rule == 23) {
            /* $task = :task :identifier :lbrace $_gen5 $_gen7 :rbrace -> Task( name=$1, declarations=$3, sections=$4 ) */
            ctx.rule = rules.get(23);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("name", 1);
            parameters.put("declarations", 3);
            parameters.put("sections", 4);
            tree.setAstTransformation(new AstTransformNodeCreator("Task", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_TASK);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_IDENTIFIER);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_LBRACE);
            tree.add(next);
            subtree = parse__gen5(ctx);
            tree.add(subtree);
            subtree = parse__gen7(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RBRACE);
            tree.add(next);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "task",
            current,
            nonterminal_first.get(127),
            rules.get(23)
        ));
    }
    public ParseTree parse__gen18(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen18(ctx);
    }
    private static ParseTree parse__gen18(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[72][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(128, "_gen18"));
        ctx.nonterminal = "_gen18";
        tree.setList(false);
        if ( current != null &&
             !nonterminal_first.get(128).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(128).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 59) {
            /* $_gen18 = $setter */
            ctx.rule = rules.get(59);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_setter(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse__gen27(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen27(ctx);
    }
    private static ParseTree parse__gen27(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[73][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(129, "_gen27"));
        ctx.nonterminal = "_gen27";
        tree.setList(true);
        if ( current != null &&
             !nonterminal_first.get(129).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(129).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 94) {
            /* $_gen27 = $wf_output $_gen28 */
            ctx.rule = rules.get(94);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_wf_output(ctx);
            tree.add(subtree);
            subtree = parse__gen28(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse__gen34(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen34(ctx);
    }
    private static ParseTree parse__gen34(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[74][current.getId()] : -1;
        ParseTree tree = new ParseTree( new NonTerminal(130, "_gen34"));
        ctx.nonterminal = "_gen34";
        tree.setList(true);
        if ( current != null &&
             !nonterminal_first.get(130).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(130).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 136) {
            /* $_gen34 = $object_kv $_gen35 */
            ctx.rule = rules.get(136);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_object_kv(ctx);
            tree.add(subtree);
            subtree = parse__gen35(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    /* Section: Lexer */
    private Map<String, List<HermesRegex>> regex = null;
    private interface LexerOutput {}
    private class LexerRegexOutput implements LexerOutput {
        public WdlTerminalIdentifier terminal;
        public int group;
        public Method function;
        LexerRegexOutput(WdlTerminalIdentifier terminal, int group, Method function) {
            this.terminal = terminal;
            this.group = group;
            this.function = function;
        }
        public String toString() {
            return String.format("<LexerRegexOutput terminal=%s, group=%d, func=%s>", this.terminal, this.group, this.function);
        }
    }
    private class LexerStackPush implements LexerOutput {
        public String mode;
        LexerStackPush(String mode) {
            this.mode = mode;
        }
    }
    private class LexerAction implements LexerOutput {
        public String action;
        LexerAction(String action) {
            this.action = action;
        }
    }
    private class HermesRegex {
        public Pattern pattern;
        public List<LexerOutput> outputs;
        HermesRegex(Pattern pattern, List<LexerOutput> outputs) {
            this.pattern = pattern;
            this.outputs = outputs;
        }
        public String toString() {
            return String.format("<HermesRegex pattern=%s, outputs=%s>", this.pattern, this.outputs);
        }
    }
    private class LineColumn {
        public int line, col;
        public LineColumn(int line, int col) {
            this.line = line;
            this.col = col;
        }
        public String toString() {
            return String.format("<LineColumn: line=%d column=%d>", this.line, this.col);
        }
    }
    private class LexerContext {
        public String string;
        public String resource;
        public int line;
        public int col;
        public Stack<String> stack;
        public Object context;
        public List<Terminal> terminals;
        LexerContext(String string, String resource) {
            this.string = string;
            this.resource = resource;
            this.line = 1;
            this.col = 1;
            this.stack = new Stack<String>();
            this.stack.push("default");
            this.terminals = new ArrayList<Terminal>();
        }
        public void advance(String match) {
            LineColumn lc = advance_line_col(match, match.length());
            this.line = lc.line;
            this.col = lc.col;
            this.string = this.string.substring(match.length());
        }
        public LineColumn advance_line_col(String match, int length) {
            LineColumn lc = new LineColumn(this.line, this.col);
            for (int i = 0; i < length && i < match.length(); i++) {
                if (match.charAt(i) == '\n') {
                    lc.line += 1;
                    lc.col = 1;
                } else {
                    lc.col += 1;
                }
            }
            return lc;
        }
    }
    private void emit(LexerContext lctx, TerminalIdentifier terminal, String source_string, int line, int col) {
        lctx.terminals.add(new Terminal(terminal.id(), terminal.string(), source_string, lctx.resource, line, col));
    }
    /**
     * The default function that is called on every regex match during lexical analysis.
     * By default, this simply calls the emit() function with all of the same parameters.
     * This can be overridden in the grammar file to provide a different default action.
     *
     * @param lctx The current state of the lexical analyzer
     * @param terminal The current terminal that was matched
     * @param source_string The source code that was matched
     * @param line The line where the match happened
     * @param col The column where the match happened
     * @return void
     */
    public void default_action(LexerContext lctx, TerminalIdentifier terminal, String source_string, int line, int col) {
        emit(lctx, terminal, source_string, line, col);
    }
    /* START USER CODE */
    private class WdlContext {
    public String wf_or_task = null;
}
public Object init() {
    return new WdlContext();
}
public void workflow(LexerContext ctx, TerminalIdentifier terminal, String source_string, int line, int col) {
    ((WdlContext) ctx.context).wf_or_task = "workflow";
    default_action(ctx, terminal, source_string, line, col);
}
public void task(LexerContext ctx, TerminalIdentifier terminal, String source_string, int line, int col) {
    ((WdlContext) ctx.context).wf_or_task = "task";
    default_action(ctx, terminal, source_string, line, col);
}
public void output(LexerContext ctx, TerminalIdentifier terminal, String source_string, int line, int col) {
    WdlContext user_ctx = (WdlContext) ctx.context;
    if (user_ctx.wf_or_task != null && user_ctx.wf_or_task.equals("workflow")) {
        ctx.stack.push("wf_output");
    }
    default_action(ctx, terminal, source_string, line, col);
}
public void unescape(LexerContext ctx, TerminalIdentifier terminal, String source_string, int line, int col) {
    default_action(ctx, terminal, StringEscapeUtils.unescapeJava(source_string.substring(1, source_string.length() - 1)), line, col);
}
    /* END USER CODE */
    public void destroy(Object context) {
        return;
    }
    private Method getFunction(String name) throws SyntaxError {
        try {
            return getClass().getMethod(
                name,
                LexerContext.class,
                TerminalIdentifier.class,
                String.class,
                int.class,
                int.class
            );
        } catch (NoSuchMethodException e) {
            throw new SyntaxError("No such method: " + name);
        }
    }
    private void lexer_init() throws SyntaxError {
        this.regex = new HashMap<String, List<HermesRegex>>();
        this.regex.put("default", Arrays.asList(new HermesRegex[] {
            new HermesRegex(
                Pattern.compile("\\s+"),
                Arrays.asList(new LexerOutput[] {
                })
            ),
            new HermesRegex(
                Pattern.compile("/\\*(.*?)\\*/", Pattern.DOTALL),
                Arrays.asList(new LexerOutput[] {
                })
            ),
            new HermesRegex(
                Pattern.compile("#.*"),
                Arrays.asList(new LexerOutput[] {
                })
            ),
            new HermesRegex(
                Pattern.compile("task(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_TASK,
                        0,
                        getFunction("task")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("(call)\\s+"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_CALL,
                        1,
                        getFunction("default_action")
                    ),
                    new LexerStackPush("task_fqn"),
                })
            ),
            new HermesRegex(
                Pattern.compile("workflow(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_WORKFLOW,
                        0,
                        getFunction("workflow")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("import(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_IMPORT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("input(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_INPUT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("output(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_OUTPUT,
                        0,
                        getFunction("output")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("as(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_AS,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("if(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_IF,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("while(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_WHILE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("runtime(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_RUNTIME,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("scatter(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_SCATTER,
                        0,
                        getFunction("default_action")
                    ),
                    new LexerStackPush("scatter"),
                })
            ),
            new HermesRegex(
                Pattern.compile("command\\s*(?=<<<)"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
                        0,
                        getFunction("default_action")
                    ),
                    new LexerStackPush("raw_command2"),
                })
            ),
            new HermesRegex(
                Pattern.compile("command\\s*(?=\\{)"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
                        0,
                        getFunction("default_action")
                    ),
                    new LexerStackPush("raw_command"),
                })
            ),
            new HermesRegex(
                Pattern.compile("parameter_meta(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("meta(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_META,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("(true|false)(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_BOOLEAN,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("(object)\\s*(\\{)"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_OBJECT,
                        0,
                        getFunction("default_action")
                    ),
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LBRACE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("(Array|Map|Object|Boolean|Int|Float|Uri|File|String)(?![a-zA-Z0-9_])(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_TYPE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("[a-zA-Z]([a-zA-Z0-9_])*"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\\"(?>[^\\\\\\\"\\n]|\\\\[\\\"\\'nrbtfav\\\\?]|\\\\[0-7]{1,3}|\\\\x[0-9a-fA-F]+|\\\\[uU]([0-9a-fA-F]{4})([0-9a-fA-F]{4})?)*\\\""),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_STRING,
                        0,
                        getFunction("unescape")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("'(?>[^\\\\\\'\\n]|\\\\[\\\"\\'nrbtfav\\\\?]|\\\\[0-7]{1,3}|\\\\x[0-9a-fA-F]+|\\\\[uU]([0-9a-fA-F]{4})([0-9a-fA-F]{4})?)*'"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_STRING,
                        0,
                        getFunction("unescape")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile(":"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_COLON,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile(","),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_COMMA,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("=="),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_DOUBLE_EQUAL,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\|\\|"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_DOUBLE_PIPE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\&\\&"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_DOUBLE_AMPERSAND,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("!="),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_NOT_EQUAL,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("="),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_EQUAL,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\."),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_DOT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\{"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LBRACE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\}"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_RBRACE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\("),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LPAREN,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\)"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_RPAREN,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\["),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LSQUARE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\]"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_RSQUARE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\+"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_PLUS,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\*"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_ASTERISK,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("-"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_DASH,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("/"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_SLASH,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("%"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_PERCENT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("<="),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LTEQ,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("<"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile(">="),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_GTEQ,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile(">"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_GT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("!"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_NOT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\?"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_QMARK,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("-?[0-9]+\\.[0-9]+"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_FLOAT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("[0-9]+"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_INTEGER,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
        }));
        this.regex.put("wf_output", Arrays.asList(new HermesRegex[] {
            new HermesRegex(
                Pattern.compile("\\s+"),
                Arrays.asList(new LexerOutput[] {
                })
            ),
            new HermesRegex(
                Pattern.compile("\\{"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LBRACE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\}"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_RBRACE,
                        0,
                        getFunction("default_action")
                    ),
                    new LexerAction("pop"),
                })
            ),
            new HermesRegex(
                Pattern.compile(","),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_COMMA,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\."),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_DOT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\*"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_ASTERISK,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("[a-zA-Z]([a-zA-Z0-9_])*(\\.[a-zA-Z]([a-zA-Z0-9_])*)*"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_FQN,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
        }));
        this.regex.put("task_fqn", Arrays.asList(new HermesRegex[] {
            new HermesRegex(
                Pattern.compile("\\s+"),
                Arrays.asList(new LexerOutput[] {
                })
            ),
            new HermesRegex(
                Pattern.compile("[a-zA-Z]([a-zA-Z0-9_])*(\\.[a-zA-Z]([a-zA-Z0-9_])*)*"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_FQN,
                        0,
                        getFunction("default_action")
                    ),
                    new LexerAction("pop"),
                })
            ),
        }));
        this.regex.put("scatter", Arrays.asList(new HermesRegex[] {
            new HermesRegex(
                Pattern.compile("\\s+"),
                Arrays.asList(new LexerOutput[] {
                })
            ),
            new HermesRegex(
                Pattern.compile("\\)"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_RPAREN,
                        0,
                        getFunction("default_action")
                    ),
                    new LexerAction("pop"),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\("),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LPAREN,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\."),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_DOT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\["),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LSQUARE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\]"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_RSQUARE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("in(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_IN,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("[a-zA-Z]([a-zA-Z0-9_])*"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
        }));
        this.regex.put("raw_command", Arrays.asList(new HermesRegex[] {
            new HermesRegex(
                Pattern.compile("\\{"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_RAW_CMD_START,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\}"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_RAW_CMD_END,
                        0,
                        getFunction("default_action")
                    ),
                    new LexerAction("pop"),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\$\\{"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_CMD_PARAM_START,
                        0,
                        getFunction("default_action")
                    ),
                    new LexerStackPush("cmd_param"),
                })
            ),
            new HermesRegex(
                Pattern.compile("(.*?)(?=\\$\\{|\\})", Pattern.DOTALL),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_CMD_PART,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
        }));
        this.regex.put("raw_command2", Arrays.asList(new HermesRegex[] {
            new HermesRegex(
                Pattern.compile("<<<"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_RAW_CMD_START,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile(">>>"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_RAW_CMD_END,
                        0,
                        getFunction("default_action")
                    ),
                    new LexerAction("pop"),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\$\\{"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_CMD_PARAM_START,
                        0,
                        getFunction("default_action")
                    ),
                    new LexerStackPush("cmd_param"),
                })
            ),
            new HermesRegex(
                Pattern.compile("(.*?)(?=\\$\\{|>>>)", Pattern.DOTALL),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_CMD_PART,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
        }));
        this.regex.put("cmd_param", Arrays.asList(new HermesRegex[] {
            new HermesRegex(
                Pattern.compile("\\s+"),
                Arrays.asList(new LexerOutput[] {
                })
            ),
            new HermesRegex(
                Pattern.compile("\\}"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_CMD_PARAM_END,
                        0,
                        getFunction("default_action")
                    ),
                    new LexerAction("pop"),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\["),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LSQUARE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\]"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_RSQUARE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("="),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_EQUAL,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\+"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_PLUS,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\*"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_ASTERISK,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("[0-9]+"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_INTEGER,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("(true|false)(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_BOOLEAN,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("(Array|Map|Object|Boolean|Int|Float|Uri|File|String)(?![a-zA-Z0-9_])(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_TYPE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("[a-zA-Z]([a-zA-Z0-9_])*(?=\\s*=)"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_CMD_ATTR_HINT,
                        -1,
                        getFunction("default_action")
                    ),
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("[a-zA-Z]([a-zA-Z0-9_])*"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile(":"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_COLON,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile(","),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_COMMA,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\."),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_DOT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("=="),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_DOUBLE_EQUAL,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\|\\|"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_DOUBLE_PIPE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\&\\&"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_DOUBLE_AMPERSAND,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("!="),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_NOT_EQUAL,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("="),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_EQUAL,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\."),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_DOT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\{"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LBRACE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\("),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LPAREN,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\)"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_RPAREN,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\["),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LSQUARE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\]"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_RSQUARE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\+"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_PLUS,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\*"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_ASTERISK,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("-"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_DASH,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("/"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_SLASH,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("%"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_PERCENT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("<="),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LTEQ,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("<"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile(">="),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_GTEQ,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile(">"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_GT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("!"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_NOT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\\"(?>[^\\\\\\\"\\n]|\\\\[\\\"\\'nrbtfav\\\\?]|\\\\[0-7]{1,3}|\\\\x[0-9a-fA-F]+|\\\\[uU]([0-9a-fA-F]{4})([0-9a-fA-F]{4})?)*\\\""),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_STRING,
                        0,
                        getFunction("unescape")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("'(?>[^\\\\\\'\\n]|\\\\[\\\"\\'nrbtfav\\\\?]|\\\\[0-7]{1,3}|\\\\x[0-9a-fA-F]+|\\\\[uU]([0-9a-fA-F]{4})([0-9a-fA-F]{4})?)*'"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_STRING,
                        0,
                        getFunction("unescape")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("-?[0-9]+\\.[0-9]+"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_FLOAT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("[0-9]+"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_INTEGER,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
        }));
    }
    private void unrecognized_token(String string, int line, int col) throws SyntaxError {
        String[] a = string.split("\n");
        String bad_line = string.split("\n")[line-1];
        StringBuffer spaces = new StringBuffer();
        for (int i = 0; i < col-1; i++) {
          spaces.append(' ');
        }
        String message = String.format(
            "Unrecognized token on line %d, column %d:\n\n%s\n%s^",
            line, col, bad_line, spaces
        );
        throw new SyntaxError(message);
    }
    private int next(LexerContext lctx) throws SyntaxError {
        String mode = lctx.stack.peek();
        for (int i = 0; i < this.regex.get(mode).size(); i++) {
            HermesRegex regex = this.regex.get(mode).get(i);
            Matcher matcher = regex.pattern.matcher(lctx.string);
            if (matcher.lookingAt()) {
                for (LexerOutput output : regex.outputs) {
                    if (output instanceof LexerStackPush) {
                        lctx.stack.push(((LexerStackPush) output).mode);
                    } else if (output instanceof LexerAction) {
                        LexerAction action = (LexerAction) output;
                        if (!action.action.equals("pop")) {
                            throw new SyntaxError("Invalid action");
                        }
                        if (action.action.equals("pop")) {
                            if (lctx.stack.empty()) {
                                throw new SyntaxError("Stack empty, cannot pop");
                            }
                            lctx.stack.pop();
                        }
                    } else if (output instanceof LexerRegexOutput) {
                        LexerRegexOutput regex_output = (LexerRegexOutput) output;
                        int group_line = lctx.line;
                        int group_col = lctx.col;
                        if (regex_output.group > 0) {
                            LineColumn lc = lctx.advance_line_col(matcher.group(0), matcher.start(regex_output.group));
                            group_line = lc.line;
                            group_col = lc.col;
                        }
                        try {
                            String source_string = (regex_output.group >= 0) ? matcher.group(regex_output.group) : "";
                            regex_output.function.invoke(
                                this,
                                lctx,
                                regex_output.terminal,
                                source_string,
                                group_line,
                                group_col
                            );
                        } catch (Exception e) {
                            e.printStackTrace();
                            throw new SyntaxError("Invalid method: " + regex_output.function);
                        }
                    }
                }
                lctx.advance(matcher.group(0));
                return matcher.group(0).length();
            }
        }
        return 0;
    }
    /**
     * Lexically analyze WDL source code, return a sequence of tokens.  Output of this
     * method should be used to construct a TerminalStream and then pass that to parse()
     *
     * @param string The WDL source code to analyze
     * @param resource A descriptor of where this code came from (usually a file path)
     * @return List of Terminal objects.
     * @throws SyntaxError If part of the source code could not lexically analyzed
     */
    public List<Terminal> lex(String string, String resource) throws SyntaxError {
        LexerContext lctx = new LexerContext(string, resource);
        Object context = this.init();
        lctx.context = context;
        String string_copy = new String(string);
        if (this.regex == null) {
            lexer_init();
        }
        while (lctx.string.length() > 0) {
            int match_length = this.next(lctx);
            if (match_length == 0) {
                this.unrecognized_token(string_copy, lctx.line, lctx.col);
            }
        }
        this.destroy(context);
        return lctx.terminals;
    }
    /* Section: Main */
}
