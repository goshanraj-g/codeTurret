package com.codeturret.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "engine")
@Data
public class EngineProperties {
    /** Maximum lines of code sent to the LLM per scan. */
    private int lineBudget = 3000;
    private Weights weights = new Weights();
    private Semgrep semgrep = new Semgrep();

    @Data
    public static class Weights {
        private double ml = 0.35;
        private double staticAnalysis = 0.30;
        private double reachability = 0.15;
        private double git = 0.10;
        private double structure = 0.10;
    }

    @Data
    public static class Semgrep {
        private boolean enabled = true;
        /** Command prefix, e.g. "semgrep" or "wsl.exe -e /home/me/.venvs/semgrep/bin/semgrep". */
        private String command = "semgrep";
        private List<String> rulesets = new ArrayList<>(List.of(
            "p/security-audit", "p/owasp-top-ten", "p/python", "p/javascript", "p/typescript", "p/java"));
        private int timeoutSeconds = 300;
    }
}
