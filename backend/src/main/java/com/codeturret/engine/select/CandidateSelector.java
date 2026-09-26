package com.codeturret.engine.select;

import com.codeturret.engine.model.Candidate;
import com.codeturret.engine.model.RepoSnapshot;

import java.util.List;

/**
 * Decides which code the LLM should look at, most important first.
 * Budgeting is applied by the caller, so selectors can be compared at equal cost.
 */
public interface CandidateSelector {

    String name();

    List<Candidate> select(RepoSnapshot repo);
}
