package dev.agentictext

import dev.agentictext.data.JsonSkillRepository
import dev.agentictext.data.SkillBackedWritingProfileRepository
import dev.agentictext.domain.DeterministicRules
import dev.agentictext.domain.ProfileId
import dev.agentictext.domain.SkillDefinition
import dev.agentictext.domain.SourceDocument
import dev.agentictext.domain.VerificationProfile
import dev.agentictext.domain.WritingProfile
import dev.agentictext.domain.WritingRequirements
import dev.agentictext.domain.WritingTask
import dev.agentictext.profiles.DefaultVerificationProfileCatalog
import dev.agentictext.verification.StandardDeterministicValidator
import java.nio.file.Paths
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillAndValidationTest {
    @Test
    fun `given the two skill exports when loaded then their full instructions and profile rules are available`() = runTest {
        val repository = JsonSkillRepository(Paths.get(System.getProperty("user.dir")))
        val skills = repository.findAll()

        assertEquals(
            setOf("lab-bench-blog", "simeon-stix-schreibstil"),
            skills.map { skill -> skill.id.value }.toSet(),
        )
        assertTrue(skills.single { it.id.value == "lab-bench-blog" }.instructions.contains("Four Content Pillars"))
        assertTrue(skills.single { it.id.value == "simeon-stix-schreibstil" }.instructions.contains("Grundhaltung"))

        val profiles = SkillBackedWritingProfileRepository(
            skillRepository = repository,
            profileCatalog = DefaultVerificationProfileCatalog(),
        )
        val germanProfile = requireNotNull(profiles.findById(ProfileId("simeon-stix-schreibstil")))
        assertTrue('ß' in germanProfile.verification.rules.forbiddenCharacters)
        assertTrue("bahnbrechend" in germanProfile.verification.rules.bannedPhrases)
    }

    @Test
    fun `given strict terminology rules when a synonym replaces an approved term then deterministic validation reports it`() {
        val profile = profile(
            rules = DeterministicRules(
                requiredSections = listOf("Overview"),
                requiredTerms = setOf("authentication token"),
                forbiddenAlternatives = mapOf("authentication token" to setOf("login credential")),
            ),
        )
        val task = task(profile, WritingRequirements(requiredSections = listOf("Overview")))

        val violations = StandardDeterministicValidator().validate(
            text = "The login credential authenticates the service.",
            task = task,
            profile = profile,
        )

        assertTrue(violations.any { it.rule == "required_section" && it.expected == "Overview" })
        assertTrue(violations.any { it.rule == "required_term" && it.expected == "authentication token" })
        assertTrue(violations.any { it.rule == "forbidden_alternative" && it.found == "login credential" })
    }

    @Test
    fun `given source fidelity rules when numbers code and modal verbs change then exact literals are rejected`() {
        val profile = profile(
            rules = DeterministicRules(
                preserveNumbersFromSources = true,
                preserveCodeFromSources = true,
                preserveModalVerbsFromSources = true,
                rejectDuplicateSentences = false,
            ),
        )
        val source = "The API must return 429 for `UserNotFoundException` at `/v1/users`."
        val task = task(
            profile,
            sources = listOf(SourceDocument("source", "API", source)),
        )

        val violations = StandardDeterministicValidator().validate(
            text = "The API should return 404 for UserNotFoundException at /v1/users.",
            task = task,
            profile = profile,
        )

        val missingLiterals = violations.filter { it.rule == "source_literal" }.mapNotNull { it.expected }.toSet()
        assertTrue("429" in missingLiterals)
        assertTrue("must" in missingLiterals)
        assertTrue("UserNotFoundException" !in missingLiterals)

        val preservedViolations = StandardDeterministicValidator().validate(
            text = "The API must return 429 for UserNotFoundException at /v1/users.",
            task = task,
            profile = profile,
        )
        assertTrue(preservedViolations.isEmpty())
    }

    @Test
    fun `given a negated modal verb in the source when the negation is removed then source fidelity rejects the change`() {
        val profile = profile(
            rules = DeterministicRules(
                preserveModalVerbsFromSources = true,
                rejectDuplicateSentences = false,
            ),
        )
        val task = task(
            profile,
            sources = listOf(SourceDocument("source", "Policy", "The process must not delete the backup.")),
        )

        val violations = StandardDeterministicValidator().validate(
            text = "The process must delete the backup.",
            task = task,
            profile = profile,
        )

        assertTrue(violations.any { it.rule == "source_literal" && it.expected == "must not" })
    }

    @Test
    fun `given unmarked prose when code preservation is enabled then ordinary words are not required as code literals`() {
        val profile = profile(
            rules = DeterministicRules(
                preserveCodeFromSources = true,
                rejectDuplicateSentences = false,
            ),
        )
        val task = task(
            profile,
            sources = listOf(SourceDocument("source", "Notes", "JavaScript uses and/or to describe either option.")),
        )

        val violations = StandardDeterministicValidator().validate(
            text = "The draft refers to JavaScript and/or another option.",
            task = task,
            profile = profile,
        )

        assertTrue(violations.isEmpty())
    }

    @Test
    fun `given the Simeon Stix profile when Swiss spelling and banned marketing language are violated then both are flagged`() {
        val skill = SkillDefinition(
            id = ProfileId("simeon-stix-schreibstil"),
            name = "Simeon Stix",
            description = "German technical writing",
            instructions = "Use Swiss orthography.",
        )
        val profile = WritingProfile(
            skill = skill,
            verification = DefaultVerificationProfileCatalog().forSkill(skill),
        )

        val violations = StandardDeterministicValidator().validate(
            text = "Das wäre maßgeblich und bahnbrechend.",
            task = task(profile),
            profile = profile,
        )

        assertTrue(violations.any { it.rule == "forbidden_character" && it.found == "ß" })
        assertTrue(violations.any { it.rule == "banned_phrase" && it.found == "bahnbrechend" })
    }

    @Test
    fun `given required sections duplicate checks and JSON format when the draft is malformed then all hard failures are reported`() {
        val profile = profile()
        val task = task(
            profile,
            WritingRequirements(requiredSections = listOf("Results"), requireValidJson = true),
        )

        val violations = StandardDeterministicValidator().validate(
            text = "Same sentence. Same sentence.",
            task = task,
            profile = profile,
        )

        assertTrue(violations.any { it.rule == "required_section" })
        assertTrue(violations.any { it.rule == "duplicate_sentence" })
        assertTrue(violations.any { it.rule == "valid_json" })
    }

    private fun profile(
        id: String = "test-profile",
        rules: DeterministicRules = DeterministicRules(),
    ): WritingProfile {
        val skill = SkillDefinition(
            id = ProfileId(id),
            name = "Test profile",
            description = "Test writing profile",
            instructions = "Follow the test style.",
        )
        return WritingProfile(
            skill = skill,
            verification = VerificationProfile(id = skill.id, rules = rules),
        )
    }

    private fun task(
        profile: WritingProfile,
        requirements: WritingRequirements = WritingRequirements(),
        sources: List<SourceDocument> = emptyList(),
    ) = WritingTask(
        prompt = "Write a test document.",
        profileId = profile.skill.id,
        requirements = requirements,
        sources = sources,
    )
}