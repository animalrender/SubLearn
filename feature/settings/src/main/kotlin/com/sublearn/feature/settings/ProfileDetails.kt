package com.sublearn.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.sublearn.core.ai.NlpModel
import com.sublearn.core.ai.NlpModelCatalog
import com.sublearn.core.ai.ProfileProcess
import com.sublearn.core.ai.ProfileStage
import com.sublearn.core.ai.ProfileStep
import com.sublearn.core.ai.ProfileTier
import com.sublearn.core.designsystem.Dimens
import com.sublearn.core.designsystem.R
import com.sublearn.core.designsystem.formatBytes
import com.sublearn.core.settings.AccuracyProfileToken

/**
 * What the chosen profile does with each line, which models it uses and what they cost (D-92).
 *
 * The steps come from [ProfileProcess], which reads the same tables the engine opens, so this list
 * cannot describe a model the profile does not run. Each model says whether it is installed and
 * whether the analysis actually loads it: a model that is only downloaded is named as such, so the
 * user does not assume the profile is using it.
 *
 * @param profile the profile whose details are shown
 * @param installedIds the catalog ids of the models on the device
 */
@Composable
fun ProfileDetails(profile: AccuracyProfileToken, installedIds: Set<String>) {
    val tier = ProfileTier.fromToken(profile.name)
    val steps = ProfileProcess.stepsFor(tier)
    val models = NlpModelCatalog.modelsFor(tier)
    val resident = NlpModelCatalog.runtimeModelsFor(tier).map { it.id }.toSet()

    Column(verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
        Text(
            text = stringResource(R.string.profile_detail_header, stringResource(profile.labelRes())),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(profile.descriptionRes()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = stringResource(R.string.profile_detail_steps),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        steps.forEachIndexed { index, step ->
            Text(
                text = "${index + 1}. ${stepText(step)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Text(
            text = stringResource(R.string.profile_detail_models),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        if (models.isEmpty()) {
            Text(
                text = stringResource(R.string.profile_detail_no_models),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        models.forEach { model ->
            ModelUsageLine(
                model = model,
                installed = model.id in installedIds,
                used = model.id in resident,
            )
        }

        if (models.isNotEmpty()) {
            val ramMb = models.maxOf { it.minRamMb }
            Text(
                text = stringResource(
                    R.string.profile_detail_totals,
                    formatBytes(NlpModelCatalog.downloadBytesFor(tier)),
                    ramMb,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.profile_detail_rank_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One model of a profile: its name, engine and size, and whether the analysis loads it. */
@Composable
private fun ModelUsageLine(model: NlpModel, installed: Boolean, used: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.xxs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = model.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "  ·  ${formatBytes(model.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(
                if (installed) R.string.profile_detail_installed else R.string.profile_detail_missing,
            ) + " · " + stringResource(
                if (used) R.string.profile_detail_used else R.string.profile_detail_unused,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (installed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
        )
    }
}

/** The sentence for one step, with the model's name filled in where the step uses a model. */
@Composable
private fun stepText(step: ProfileStep): String {
    val model = step.model?.name.orEmpty()
    return when (step.stage) {
        ProfileStage.PHRASES -> stringResource(R.string.profile_stage_phrases)
        ProfileStage.LOOKUP -> stringResource(R.string.profile_stage_lookup)
        ProfileStage.TAGGING -> stringResource(R.string.profile_stage_tagging)
        ProfileStage.RANK_PRIOR -> stringResource(R.string.profile_stage_prior)
        ProfileStage.RANK_ENCODER -> stringResource(R.string.profile_stage_encoder, model)
        ProfileStage.RANK_LLM -> stringResource(R.string.profile_stage_llm, model)
    }
}
