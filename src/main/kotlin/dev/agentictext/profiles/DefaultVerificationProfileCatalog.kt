package dev.agentictext.profiles

import dev.agentictext.data.VerificationProfileCatalog
import dev.agentictext.domain.DeterministicRules
import dev.agentictext.domain.ProfileId
import dev.agentictext.domain.QualityRubric
import dev.agentictext.domain.SkillDefinition
import dev.agentictext.domain.VerificationProfile

class DefaultVerificationProfileCatalog : VerificationProfileCatalog {
    override fun forSkill(skill: SkillDefinition): VerificationProfile {
        val rules = when (skill.id.value) {
            LAB_BENCH_BLOG_ID -> DeterministicRules(
                bannedPhrases = setOf(
                    "in this post, i will",
                    "it's worth noting",
                    "at the end of the day",
                    "in conclusion",
                    "as we can see",
                ),
            )

            SIMEON_STIX_STYLE_ID -> DeterministicRules(
                bannedPhrases = setOf("revolutionieren", "bahnbrechend", "massgeblich"),
                forbiddenCharacters = setOf('ß'),
            )

            else -> DeterministicRules()
        }

        return VerificationProfile(
            id = skill.id,
            rules = rules,
            rubric = QualityRubric.standard(),
        )
    }

    private companion object {
        const val LAB_BENCH_BLOG_ID = "lab-bench-blog"
        const val SIMEON_STIX_STYLE_ID = "simeon-stix-schreibstil"
    }
}