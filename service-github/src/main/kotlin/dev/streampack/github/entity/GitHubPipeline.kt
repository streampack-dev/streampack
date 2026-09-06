/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.entity

import dev.streampack.forge.model.PipelineOutcome
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

/** The last outcome seen for a workflow run, so a settlement is reported once and retries again */
@Entity
@Table(name = "github_pipelines")
data class GitHubPipeline(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repo_id", nullable = false)
    val repo: GitHubRepo = GitHubRepo(),
    @Column(nullable = false, length = 64, name = "pipeline_id") val pipelineId: String = "",
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, name = "last_status")
    val lastStatus: PipelineOutcome = PipelineOutcome.IN_PROGRESS,
    @Column(nullable = false) val updatedAt: Instant = Instant.now(),
)
