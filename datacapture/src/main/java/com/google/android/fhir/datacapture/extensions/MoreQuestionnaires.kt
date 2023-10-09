/*
 * Copyright 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.fhir.datacapture.extensions

import org.hl7.fhir.exceptions.FHIRException
import org.hl7.fhir.r4.model.CanonicalType
import org.hl7.fhir.r4.model.CodeType
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Expression
import org.hl7.fhir.r4.model.Extension
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.ResourceType

/**
 * The StructureMap url in the
 * [target structure-map extension](http://build.fhir.org/ig/HL7/sdc/StructureDefinition-sdc-questionnaire-targetStructureMap.html)
 * s.
 */
val Questionnaire.targetStructureMap: String?
  get() {
    val extensionValue =
      this.extension.singleOrNull { it.url == TARGET_STRUCTURE_MAP }?.value ?: return null
    return if (extensionValue is CanonicalType) extensionValue.valueAsString else null
  }

internal val Questionnaire.variableExpressions: List<Expression>
  get() =
    this.extension.filter { it.url == EXTENSION_VARIABLE_URL }.map { it.castToExpression(it.value) }

/**
 * A list of extensions that define the resources that provide context for form processing logic:
 * https://build.fhir.org/ig/HL7/sdc/StructureDefinition-sdc-questionnaire-launchContext.html
 */
internal val Questionnaire.questionnaireLaunchContexts: List<Extension>?
  get() =
    this.extension
      .filter { it.url == EXTENSION_SDC_QUESTIONNAIRE_LAUNCH_CONTEXT }
      .takeIf { it.isNotEmpty() }

/**
 * Finds the specific variable name [String] at questionnaire [Questionnaire] level
 *
 * @param variableName the [String] to match the variable at questionnaire [Questionnaire] level
 * @return [Expression] the matching expression
 */
internal fun Questionnaire.findVariableExpression(variableName: String): Expression? =
  variableExpressions.find { it.name == variableName }

/**
 * Validates each questionnaire launch context extension matches:
 * https://build.fhir.org/ig/HL7/sdc/StructureDefinition-sdc-questionnaire-launchContext.html
 */
internal fun validateLaunchContextExtensions(launchContextExtensions: List<Extension>) =
  launchContextExtensions.forEach { launchExtension ->
    validateLaunchContextExtension(launchExtension)
  }

/**
 * Verifies the existence of extension:name and extension:type with valid name system and type
 * values.
 */
private fun validateLaunchContextExtension(launchExtension: Extension) {
  check(launchExtension.extension.size == 2) {
    "Expected 2 extensions (name and type) in $EXTENSION_SDC_QUESTIONNAIRE_LAUNCH_CONTEXT but found ${launchExtension.extension.size}."
  }

  val nameCoding =
    launchExtension.getExtensionByUrl("name")?.value as? Coding
      ?: error(
        "The extension:name is missing or is not of type Coding in $EXTENSION_SDC_QUESTIONNAIRE_LAUNCH_CONTEXT"
      )

  val typeCodeType =
    launchExtension.getExtensionByUrl("type")?.value as? CodeType
      ?: error(
        "The extension:type is missing or is not of type CodeType in $EXTENSION_SDC_QUESTIONNAIRE_LAUNCH_CONTEXT"
      )

  val isValidResourceType =
    try {
      ResourceType.fromCode(typeCodeType.value) != null
    } catch (exception: FHIRException) {
      false
    }

  if (nameCoding.system != EXTENSION_LAUNCH_CONTEXT || !isValidResourceType) {
    error(
      "The extension:name and/or extension:type do not follow the format specified in $EXTENSION_SDC_QUESTIONNAIRE_LAUNCH_CONTEXT"
    )
  }
}

/**
 * Filters the provided launch contexts by matching the keys with the `code` values found in the
 * "name" extensions.
 */
internal fun filterByCodeInNameExtension(
  launchContexts: Map<String, Resource>,
  launchContextExtensions: List<Extension>
): Map<String, Resource> {
  val nameCodes =
    launchContextExtensions
      .mapNotNull { extension -> (extension.getExtensionByUrl("name").value as? Coding)?.code }
      .toSet()

  return launchContexts.filterKeys { nameCodes.contains(it) }
}

/**
 * See
 * [Extension: target structure map](http://build.fhir.org/ig/HL7/sdc/StructureDefinition-sdc-questionnaire-targetStructureMap.html)
 * .
 */
private const val TARGET_STRUCTURE_MAP: String =
  "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-targetStructureMap"

val Questionnaire.isPaginated: Boolean
  get() = item.any { item -> item.displayItemControl == DisplayItemControlType.PAGE }

/**
 * See
 * [Extension: Entry mode](http://build.fhir.org/ig/HL7/sdc/StructureDefinition-sdc-questionnaire-entryMode.html)
 * .
 */
internal const val EXTENSION_ENTRY_MODE_URL: String =
  "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-entryMode"

internal const val EXTENSION_SDC_QUESTIONNAIRE_LAUNCH_CONTEXT =
  "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-launchContext"

internal const val EXTENSION_LAUNCH_CONTEXT = "http://hl7.org/fhir/uv/sdc/CodeSystem/launchContext"

val Questionnaire.entryMode: EntryMode?
  get() {
    val entryMode =
      this.extension
        .firstOrNull { it.url == EXTENSION_ENTRY_MODE_URL }
        ?.value
        ?.toString()
        ?.lowercase()
    return EntryMode.from(entryMode)
  }

enum class EntryMode(val value: String) {
  PRIOR_EDIT("prior-edit"),
  RANDOM("random"),
  SEQUENTIAL("sequential");

  companion object {
    fun from(type: String?): EntryMode? = values().find { it.value == type }
  }
}
