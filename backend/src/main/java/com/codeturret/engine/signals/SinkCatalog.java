package com.codeturret.engine.signals;

import com.codeturret.engine.model.CodeUnit;
import com.codeturret.engine.parse.CodeParser;

import java.util.*;

/**
 * Calls that are dangerous when they receive attacker-controlled data. Matched against call nodes from the
 * syntax tree (not substrings), either by qualified {@code receiver.method} or by distinctive simple name.
 */
public final class SinkCatalog {

    public enum Category { COMMAND, CODE_EVAL, SQL, FILE, DESERIALIZATION, XML, HTTP_OUT, REDIRECT, TEMPLATE, CRYPTO, TLS }

    private static final Map<String, Category> SIMPLE = new HashMap<>();
    private static final Map<String, Category> QUALIFIED = new HashMap<>();

    static {
        simple(Category.COMMAND, "exec", "execSync", "spawn", "spawnSync", "execFile", "system", "popen", "Popen",
            "check_output", "check_call", "ProcessBuilder");
        simple(Category.CODE_EVAL, "eval", "Function", "compile", "vm.runInNewContext", "runInThisContext");
        simple(Category.SQL, "execute", "executemany", "executescript", "raw", "rawQuery", "executeQuery",
            "executeUpdate", "createNativeQuery", "createQuery", "queryForObject", "queryForList", "$where");
        simple(Category.FILE, "send_file", "sendFile", "readFile", "readFileSync", "writeFile", "writeFileSync",
            "createReadStream", "unlink", "FileInputStream", "FileReader", "FileOutputStream");
        simple(Category.DESERIALIZATION, "readObject", "unserialize", "deserialize", "fromXML", "unsafe_load");
        simple(Category.XML, "newDocumentBuilder", "newSAXParser", "createXMLStreamReader", "XMLReader");
        simple(Category.HTTP_OUT, "urlopen", "openConnection", "fetch");
        simple(Category.REDIRECT, "redirect", "sendRedirect");
        simple(Category.TEMPLATE, "render_template_string", "Template", "mark_safe", "Markup");
        simple(Category.CRYPTO, "md5", "sha1", "Random", "createCipher");
        simple(Category.TLS, "X509TrustManager", "HostnameVerifier", "setHostnameVerifier");

        qualified(Category.SQL, "cursor.execute", "db.query", "jdbc.query", "connection.query", "pool.query",
            "conn.query", "sequelize.query", "knex.raw");
        qualified(Category.DESERIALIZATION, "pickle.loads", "pickle.load", "yaml.load", "marshal.loads",
            "jsonpickle.decode");
        qualified(Category.HTTP_OUT, "requests.get", "requests.post", "requests.request", "httpx.get",
            "axios.get", "axios.post", "needle.get", "http.get", "https.get", "urllib.request");
        qualified(Category.FILE, "builtins.open", "os.remove", "shutil.rmtree", "Files.readAllBytes",
            "Files.newInputStream", "Paths.get");
        qualified(Category.CODE_EVAL, "setTimeout", "setInterval");
        qualified(Category.CRYPTO, "Math.random", "hashlib.md5", "hashlib.sha1");
        qualified(Category.TEMPLATE, "res.send", "res.write");
    }

    private SinkCatalog() {}

    private static void simple(Category c, String... names) {
        for (String n : names) SIMPLE.put(n, c);
    }

    private static void qualified(Category c, String... names) {
        for (String n : names) QUALIFIED.put(n, c);
    }

    public static Optional<Category> categorize(String call) {
        Category q = QUALIFIED.get(call);
        if (q != null) return Optional.of(q);
        return Optional.ofNullable(SIMPLE.get(CodeParser.lastSegment(call)));
    }

    public static Set<Category> sinksIn(CodeUnit unit) {
        EnumSet<Category> found = EnumSet.noneOf(Category.class);
        for (String call : unit.calls()) categorize(call).ifPresent(found::add);
        return found;
    }
}
