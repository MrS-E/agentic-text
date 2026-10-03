package dev.agentictext.data

import dev.agentictext.domain.ProfileId
import dev.agentictext.domain.SkillDefinition
import dev.agentictext.domain.VerificationProfile
import dev.agentictext.domain.WritingProfile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

interface SkillRepository {
    suspend fun findById(id: ProfileId): SkillDefinition?

    suspend fun findAll(): List<SkillDefinition>
}

interface WritingProfileRepository {
    suspend fun findById(id: ProfileId): WritingProfile?
}

fun interface VerificationProfileCatalog {
    fun forSkill(skill: SkillDefinition): VerificationProfile
}

class SkillBackedWritingProfileRepository(
    private val skillRepository: SkillRepository,
    private val profileCatalog: VerificationProfileCatalog,
) : WritingProfileRepository {
    override suspend fun findById(id: ProfileId): WritingProfile? =
        skillRepository.findById(id)?.let { skill ->
            WritingProfile(skill = skill, verification = profileCatalog.forSkill(skill))
        }
}

class JsonSkillRepository(
    private val exportDirectory: Path,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : SkillRepository {
    private val cacheMutex = Mutex()

    @Volatile
    private var cachedSkills: List<SkillDefinition>? = null

    override suspend fun findById(id: ProfileId): SkillDefinition? =
        findAll().firstOrNull { skill -> skill.id == id }

    override suspend fun findAll(): List<SkillDefinition> {
        cachedSkills?.let { cached -> return cached }
        cacheMutex.lock()
        try {
            cachedSkills?.let { cached -> return cached }
            val loaded = withContext(ioDispatcher) { readSkillExports() }
            cachedSkills = loaded
            return loaded
        } finally {
            cacheMutex.unlock()
        }
    }

    private fun readSkillExports(): List<SkillDefinition> {
        require(Files.isDirectory(exportDirectory)) {
            "Skill export directory does not exist: $exportDirectory"
        }

        val files = Files.newDirectoryStream(exportDirectory, "*.json").use { paths ->
            paths.toList().sortedBy { path -> path.fileName.toString() }
        }
        val skills = files.flatMap { path ->
            val contents = try {
                Files.readString(path)
            } catch (exception: IOException) {
                throw IOException("Could not read skill export '$path'", exception)
            }
            val exports = try {
                json.decodeFromString<List<SkillExportDto>>(contents)
            } catch (exception: SerializationException) {
                throw IOException("Invalid skill export JSON in '$path'", exception)
            }
            exports.map { dto ->
                SkillDefinition(
                    id = ProfileId(dto.id),
                    name = dto.name,
                    description = dto.description,
                    instructions = dto.content,
                )
            }
        }
        require(skills.map { it.id }.distinct().size == skills.size) {
            "Skill export directory contains duplicate skill ids"
        }
        return skills
    }
}

@Serializable
private data class SkillExportDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("description") val description: String,
    @SerialName("content") val content: String,
)