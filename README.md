# Agentic Text

A provider-neutral Kotlin/JVM core for generating, verifying, selecting, and revising text. The two skill exports at the repository root are loaded as complete writing instructions; verification policy is maintained separately so free-form style guidance is not mistaken for deterministic rules.

## Pipeline

1. Load the requested skill and its verification profile.
2. Generate a small batch (three candidates by default, capped at eight).
3. Run deterministic checks first: requested sections and terms, exact terminology, banned alternatives, word limits, protected source literals, duplicate sentences, Swiss `ss` spelling, and optional JSON validity.
4. For candidates passing hard checks, extract factual claims, retrieve evidence from supplied documents, and verify each claim against source excerpts.
5. Score candidates against a weighted rubric; every criterion score needs rationale and evidence. The pipeline recomputes acceptance and does not trust the evaluator's reported `pass` value.
6. Critique specific failures, revise at most once by default, and re-run verification on the exact final draft. Return `Escalated` when confidence/evidence is insufficient; hard-check failures are `Rejected`.

The Lab Bench profile rejects its explicit fluff phrases. The Simeon Stix German profile enforces Swiss orthography (`ß` is rejected) and rejects listed marketing terms. Both complete Markdown skill bodies are still provided to writer/evaluator prompts, including rules that require semantic judgment rather than a deterministic string check.

## Connect a model provider

Implement `TextGenerationClient` with the model provider of your choice. The repository intentionally includes no provider SDK, API key, or network endpoint because none was specified. Use separate clients/models for writing and evaluation when available; the evaluator, critic, claim extractor, and claim judge each have their own prompt adapter.

```kotlin
val skillRepository = JsonSkillRepository(Paths.get("."))
val profileRepository = SkillBackedWritingProfileRepository(
    skillRepository = skillRepository,
    profileCatalog = DefaultVerificationProfileCatalog(),
)

val writingModel: TextGenerationClient = /* provider adapter */
val evaluationModel: TextGenerationClient = /* preferably a separate model */
val claimExtractor = PromptedClaimExtractor(evaluationModel)
val factChecker = EvidenceBackedClaimVerifier(
    claimExtractor = claimExtractor,
    evidenceRetriever = KeywordEvidenceRetriever(),
    claimJudge = PromptedClaimJudge(evaluationModel),
)
val pipeline = WritingPipeline(
    profileRepository = profileRepository,
    writer = PromptedWritingAgent(writingModel),
    claimVerifier = factChecker,
    evaluator = PromptedDraftEvaluator(evaluationModel),
    critic = PromptedDraftCritic(evaluationModel),
)

val task = WritingTask(
    prompt = "Write a technical post about the experiment and its findings.",
    profileId = ProfileId("lab-bench-blog"),
    sources = listOf(SourceDocument(id = "notes", title = "Experiment notes", content = sourceText)),
    requirements = WritingRequirements(
        requiredSections = listOf("Findings"),
        exactTerms = setOf("authentication token"),
        forbiddenAlternatives = mapOf("authentication token" to setOf("login credential")),
        preserveNumbers = true,
        preserveCode = true,
        preserveModalVerbs = true,
    ),
)
val outcome = pipeline.run(task)
```

`KeywordEvidenceRetriever` is a small local baseline over the task's supplied documents, not a web search or vector database. Replace it with an implementation of `EvidenceRetriever` for a trusted document store or search service. Evidence is checked against the original document before a claim can be considered supported.

For technical documentation, configure `WritingRequirements` with the approved exact terms, forbidden alternatives, and source-preservation flags. Use `exactTerms` for case-sensitive API names, file paths, commands, exception/product identifiers, and `exactPhrases` for wording that must remain verbatim. `preserveCode` automatically protects source code marked with backticks or fenced code blocks; unmarked prose is not treated as a code literal. The `SOURCE_FIDELITY` evaluator criterion handles meaning-level checks such as scope, exceptions, and modal strength; provide source documents so its judgment can cite evidence.

## Build and test

Requires JDK 21. Run the test suite with:

```shell
./gradlew test
```