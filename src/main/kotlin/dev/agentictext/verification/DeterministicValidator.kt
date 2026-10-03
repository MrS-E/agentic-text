package dev.agentictext.verification

import dev.agentictext.domain.DeterministicRules
import dev.agentictext.domain.HardViolation
import dev.agentictext.domain.WritingProfile
import dev.agentictext.domain.WritingRequirements
import dev.agentictext.domain.WritingTask
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.Locale

fun interface DeterministicValidator {
    fun validate(text: String, task: WritingTask, profile: WritingProfile): List<HardViolation>
}

class StandardDeterministicValidator(
    private val json: Json = Json,
) : DeterministicValidator {
    override fun validate(
        text: String,
        task: WritingTask,
        profile: WritingProfile,
    ): List<HardViolation> {
        val violations = mutableListOf<HardViolation>()
        if (text.isBlank()) {
            return listOf(HardViolation(rule = "non_empty", message = "Draft is empty"))
        }

        val profileRules = profile.verification.rules
        val requirements = task.requirements
        val minimumWords = requirements.minWords ?: profileRules.minWords
        val maximumWords = requirements.maxWords ?: profileRules.maxWords
        val wordCount = wordPattern.findAll(text).count()

        if (minimumWords != null && wordCount < minimumWords) {
            violations += HardViolation(
                rule = "minimum_words",
                message = "Draft is shorter than the minimum word count",
                expected = minimumWords.toString(),
                found = wordCount.toString(),
            )
        }
        if (maximumWords != null && wordCount > maximumWords) {
            violations += HardViolation(
                rule = "maximum_words",
                message = "Draft exceeds the maximum word count",
                expected = maximumWords.toString(),
                found = wordCount.toString(),
            )
        }

        val requiredSections = profileRules.requiredSections + requirements.requiredSections
        val headingTexts = headingPattern.findAll(text)
            .map { match -> normalize(match.groupValues[1]) }
            .toSet()
        requiredSections.distinct().filterNot { section -> normalize(section) in headingTexts }
            .forEach { section ->
                violations += HardViolation(
                    rule = "required_section",
                    message = "Required section is missing",
                    expected = section,
                )
            }

        val requiredTerms = profileRules.requiredTerms + requirements.requiredTerms
        requiredTerms.forEach { term ->
            if (!containsPhrase(text, term, ignoreCase = true)) {
                violations += HardViolation(
                    rule = "required_term",
                    message = "Required terminology is missing",
                    expected = term,
                )
            }
        }

        val exactTerms = profileRules.exactTerms + requirements.exactTerms
        exactTerms.forEach { term ->
            if (!containsPhrase(text, term, ignoreCase = false)) {
                violations += HardViolation(
                    rule = "exact_term",
                    message = "Required technical term or identifier was not preserved exactly",
                    expected = term,
                )
            }
        }

        val forbiddenTerms = profileRules.forbiddenTerms + requirements.forbiddenTerms
        forbiddenTerms.forEach { term ->
            if (containsPhrase(text, term, ignoreCase = true)) {
                violations += HardViolation(
                    rule = "forbidden_term",
                    message = "Draft contains a forbidden term",
                    found = term,
                )
            }
        }

        mergeAlternatives(profileRules, requirements).forEach { (preferred, alternatives) ->
            alternatives.forEach { alternative ->
                if (containsPhrase(text, alternative, ignoreCase = true)) {
                    violations += HardViolation(
                        rule = "forbidden_alternative",
                        message = "Use the approved terminology instead of this alternative",
                        expected = preferred,
                        found = alternative,
                    )
                }
            }
        }

        (profileRules.exactPhrases + requirements.exactPhrases).forEach { phrase ->
            if (!containsPhrase(text, phrase, ignoreCase = false)) {
                violations += HardViolation(
                    rule = "exact_phrase",
                    message = "Required source wording was not preserved",
                    expected = phrase,
                )
            }
        }

        profileRules.bannedPhrases.forEach { phrase ->
            if (containsPhrase(text, phrase, ignoreCase = true)) {
                violations += HardViolation(
                    rule = "banned_phrase",
                    message = "Draft contains a phrase prohibited by this writing profile",
                    found = phrase,
                )
            }
        }

        profileRules.forbiddenCharacters.forEach { character ->
            if (character in text) {
                violations += HardViolation(
                    rule = "forbidden_character",
                    message = "Draft contains a character prohibited by this writing profile",
                    expected = "Character other than '$character'",
                    found = character.toString(),
                )
            }
        }

        collectProtectedSourceLiterals(task, profileRules).forEach { literal ->
            if (literal !in text) {
                violations += HardViolation(
                    rule = "source_literal",
                    message = "A protected source number, identifier, code literal, or URL is missing or changed",
                    expected = literal,
                )
            }
        }

        if (profileRules.rejectDuplicateSentences) {
            duplicateSentences(text).forEach { sentence ->
                violations += HardViolation(
                    rule = "duplicate_sentence",
                    message = "Draft contains a duplicate sentence",
                    found = sentence,
                )
            }
        }

        if (requirements.requireValidJson) {
            try {
                json.parseToJsonElement(text)
            } catch (_: SerializationException) {
                violations += HardViolation(rule = "valid_json", message = "Draft is not valid JSON")
            }
        }

        return violations
    }

    private fun mergeAlternatives(
        profileRules: DeterministicRules,
        requirements: WritingRequirements,
    ): Map<String, Set<String>> = buildMap {
        profileRules.forbiddenAlternatives.forEach { (preferred, alternatives) ->
            put(preferred, alternatives.toSet())
        }
        requirements.forbiddenAlternatives.forEach { (preferred, alternatives) ->
            put(preferred, get(preferred).orEmpty() + alternatives)
        }
    }

    private fun collectProtectedSourceLiterals(
        task: WritingTask,
        profileRules: DeterministicRules,
    ): Set<String> {
        val preserveNumbers = profileRules.preserveNumbersFromSources || task.requirements.preserveNumbers
        val preserveCode = profileRules.preserveCodeFromSources || task.requirements.preserveCode
        val preserveUrls = profileRules.preserveUrlsFromSources || task.requirements.preserveUrls
        val preserveModalVerbs =
            profileRules.preserveModalVerbsFromSources || task.requirements.preserveModalVerbs
        if (!preserveNumbers && !preserveCode && !preserveUrls && !preserveModalVerbs) return emptySet()

        return buildSet {
            task.sources.forEach { source ->
                if (preserveNumbers) {
                    numberPattern.findAll(source.content).forEach { match -> add(match.value.trim()) }
                }
                if (preserveCode) {
                    inlineCodePattern.findAll(source.content).forEach { match -> add(match.groupValues[1]) }
                    fencedCodePattern.findAll(source.content).forEach { match ->
                        match.groupValues[1].trim().takeIf(String::isNotBlank)?.let(::add)
                    }
                }
                if (preserveUrls) {
                    urlPattern.findAll(source.content).forEach { match ->
                        add(match.value.trimEnd('.', ',', ';', ':', '!', '?'))
                    }
                }
                if (preserveModalVerbs) {
                    modalVerbPattern.findAll(source.content).forEach { match -> add(match.value) }
                }
            }
        }
    }

    private fun duplicateSentences(text: String): Set<String> = sentencePattern.split(text)
        .map { sentence -> sentence.trim() }
        .filter(String::isNotBlank)
        .groupBy(::normalize)
        .filterValues { sentences -> sentences.size > 1 }
        .values.mapNotNull { sentences -> sentences.firstOrNull() }
        .toSet()

    private fun containsPhrase(text: String, phrase: String, ignoreCase: Boolean): Boolean {
        if (phrase.isBlank()) return true
        val normalizedPhrase = phrase.trim().split(Regex("\\s+"))
            .joinToString("\\s+") { token -> Regex.escape(token) }
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val pattern = Regex(
            "(?<![\\p{L}\\p{N}_])$normalizedPhrase(?![\\p{L}\\p{N}_])",
            options,
        )
        return pattern.containsMatchIn(text)
    }

    private fun normalize(text: String): String = text.lowercase(Locale.ROOT)
        .replace(Regex("[\\p{Punct}\\s]+"), " ")
        .trim()

    private companion object {
        val wordPattern = Regex("[\\p{L}\\p{N}]+(?:['’_-][\\p{L}\\p{N}]+)*")
        val headingPattern = Regex("(?m)^\\s{0,3}#{1,6}\\s+(.+?)\\s*#*\\s*$")
        val sentencePattern = Regex("(?<=[.!?])\\s+")
        val numberPattern = Regex(
            "(?<![\\p{L}\\p{N}_])\\d+(?:[.,]\\d+)*(?:\\s?(?:ms|min|s|h|KB|MB|GB|TB|B|°C|°F|km|m|kg|%))?(?![\\p{L}\\p{N}_])",
        )
        val inlineCodePattern = Regex("`([^`]+)`")
        val fencedCodePattern = Regex("(?s)```(?:[\\w+-]+)?\\s*(.*?)```")
        val urlPattern = Regex("https?://[^\\s<>)]+", RegexOption.IGNORE_CASE)
        val modalVerbPattern = Regex(
            "(?<![\\p{L}\\p{N}_])(?:must not|should not|may not|can not|shall not|muss nicht|müssen nicht|soll nicht|sollte nicht|sollen nicht|darf nicht|dürfen nicht|kann nicht|können nicht|cannot|can't|mustn't|shouldn't|couldn't|shall|must|should|may|can|müssen|muss|sollte|sollen|soll|dürfen|darf|können|kann)(?![\\p{L}\\p{N}_])",
            RegexOption.IGNORE_CASE,
        )
    }
}