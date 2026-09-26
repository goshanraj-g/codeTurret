package com.codeturret.engine.parse;

import com.codeturret.engine.model.CodeUnit;
import com.codeturret.engine.model.Language;
import com.codeturret.engine.model.SourceFile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CodeParserTest {

    private final CodeParser parser = new CodeParser();

    private ParsedFile parse(String path, String content) {
        return parser.parse(new SourceFile(path, Language.fromPath(path), content));
    }

    private static Optional<CodeUnit> unit(ParsedFile f, String name) {
        return f.units().stream().filter(u -> u.name().equals(name)).findFirst();
    }

    @Test
    void pythonDecoratedRouteIsAnEntrypointIncludingDecorator() {
        ParsedFile f = parse("app.py", """
            import os
            from flask import Flask

            app = Flask(__name__)
            app.config["DEBUG"] = False

            @app.route("/run")
            def run(cmd):
                os.system(cmd)

            def helper(x):
                return x + 1
            """);

        CodeUnit run = unit(f, "run").orElseThrow();
        assertThat(run.entrypoint()).isTrue();
        assertThat(run.startLine()).isEqualTo(7);
        assertThat(run.endLine()).isEqualTo(9);
        assertThat(run.calls()).contains("os.system");
        assertThat(unit(f, "helper").orElseThrow().entrypoint()).isFalse();

        CodeUnit module = unit(f, "<module>").orElseThrow();
        assertThat(module.kind()).isEqualTo(CodeUnit.Kind.MODULE);
        assertThat(module.startLine()).isEqualTo(4);
    }

    @Test
    void pythonMethodsAreQualifiedByClass() {
        ParsedFile f = parse("dao.py", """
            class Student:
                @staticmethod
                async def create(conn, name):
                    q = "INSERT INTO students (name) VALUES ('%(name)s')" % {'name': name}
                    async with conn.cursor() as cur:
                        await cur.execute(q)
            """);

        CodeUnit create = unit(f, "Student.create").orElseThrow();
        assertThat(create.kind()).isEqualTo(CodeUnit.Kind.METHOD);
        assertThat(create.simpleName()).isEqualTo("create");
        assertThat(create.calls()).contains("cur.execute");
    }

    @Test
    void javascriptConstructorStyleContainersAreReplacedByTheirMembers() {
        // NodeGoat style: a function whose body is mostly this.x = (req, res) => {...} handlers.
        ParsedFile f = parse("routes/allocations.js", """
            function AllocationsHandler(db) {
                const dao = new AllocationsDAO(db);
                this.displayAllocations = (req, res, next) => {
                    const { userId } = req.params;
                    dao.getByUserId(userId, (err, a) => res.render("allocations", a));
                };
                this.other = (req, res) => {
                    res.send("ok");
                };
            }
            module.exports = AllocationsHandler;
            """);

        assertThat(unit(f, "AllocationsHandler")).isEmpty();
        CodeUnit display = unit(f, "displayAllocations").orElseThrow();
        assertThat(display.startLine()).isEqualTo(3);
        assertThat(display.calls()).contains("dao.getByUserId");
    }

    @Test
    void javascriptRouteRegistrationsProduceHandlerRefsAndInlineEntrypoints() {
        ParsedFile f = parse("server.js", """
            const handlers = require("./handlers");
            app.get("/benefits", isLoggedIn, handlers.displayBenefits);
            app.post("/echo", (req, res) => {
                res.send(req.body.text);
            });
            """);

        assertThat(f.routeHandlerRefs()).contains("isLoggedIn", "displayBenefits");
        CodeUnit inline = unit(f, "POST /echo").orElseThrow();
        assertThat(inline.entrypoint()).isTrue();
        assertThat(inline.calls()).contains("res.send");
    }

    @Test
    void exportsAssignmentIsNamedByProperty() {
        ParsedFile f = parse("routes/search.js", """
            exports.results = async (req, res) => {
              res.send("<h1>" + req.query.q + "</h1>");
            };
            """);
        assertThat(unit(f, "results")).isPresent();
    }

    @Test
    void typescriptAndTsxParse() {
        ParsedFile ts = parse("lib/redirect.ts", """
            import type { Request, Response } from "express";

            export function followNext(req: Request, res: Response) {
              const next = String(req.query.next || "/");
              return res.redirect(next);
            }
            """);
        assertThat(unit(ts, "followNext").orElseThrow().calls()).contains("res.redirect");

        ParsedFile tsx = parse("components/Comment.tsx", """
            export function Comment({ body }: { body: string }) {
              return <div dangerouslySetInnerHTML={{ __html: body }} />;
            }
            """);
        assertThat(unit(tsx, "Comment")).isPresent();
    }

    @Test
    void javaControllersFieldsAndCalls() {
        ParsedFile f = parse("InvoiceController.java", """
            package shop;

            public class InvoiceController {
                private final Random random = new Random();
                private final JdbcTemplate jdbc;

                @GetMapping("/invoices")
                public List<Invoice> list(@RequestParam String customer) {
                    return repo.findByCustomer(customer);
                }

                List<Invoice> findByCustomer(String c) {
                    return jdbc.query("SELECT * FROM invoices WHERE customer = '" + c + "'", mapper);
                }
            }
            """);

        CodeUnit list = unit(f, "InvoiceController.list").orElseThrow();
        assertThat(list.entrypoint()).isTrue();
        assertThat(unit(f, "InvoiceController.findByCustomer").orElseThrow().calls()).contains("jdbc.query");

        List<CodeUnit> modules = f.units().stream().filter(u -> u.kind() == CodeUnit.Kind.MODULE).toList();
        assertThat(modules).anySatisfy(m -> {
            assertThat(m.startLine()).isLessThanOrEqualTo(4);
            assertThat(m.calls()).contains("Random");
        });
    }

    @Test
    void unsupportedLanguageBecomesOneModuleUnit() {
        ParsedFile f = parser.parse(new SourceFile("x.rb", Language.OTHER, "puts 'a'\nsystem(params[:cmd])\n"));
        assertThat(f.units()).hasSize(1);
        assertThat(f.units().get(0).kind()).isEqualTo(CodeUnit.Kind.MODULE);
    }
}
