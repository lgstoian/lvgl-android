// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.annotations

import com.lvgl.android.guards.GuardRules

/**
 * Pure validation logic for machine-readable annotations, extracted so
 * GuardCanaryTest can prove it catches dangling/orphaned references and
 * accepts clean input (no false green, no false positive).
 *
 * @breadcrumb BC-015
 */
object AnnotationValidator {

    fun validate(
        annotations: List<MachineCommentParser.Annotation>,
        trail: Set<String>,
        adrs: Set<String>,
        testClasses: Set<String>,
        ruleNames: Set<String> = GuardRules.RULE_NAMES
    ): List<String> {
        val errors = mutableListOf<String>()
        val referenced = mutableSetOf<String>()

        annotations.forEach { a ->
            val where = "${a.file}:${a.line}"
            if (a.tag !in MachineCommentParser.KNOWN_TAGS) {
                errors.add("$where: unknown tag '@${a.tag}'")
                return@forEach
            }
            when (a.tag) {
                "breadcrumb" -> {
                    val id = a.refId
                    if (id == null) {
                        errors.add("$where: @breadcrumb has no BC-NNN reference")
                    } else {
                        referenced.add(id)
                        if (id !in trail) errors.add("$where: dangling @breadcrumb $id")
                    }
                }
                "decision" -> {
                    val id = a.refId
                    if (id == null) {
                        errors.add("$where: @decision has no ADR-NNN reference")
                    } else if (id !in adrs) {
                        errors.add("$where: dangling @decision $id")
                    }
                }
                "machine-json" -> {
                    val json = a.pairs["json"]
                    if (json == null || !MiniJson.isValid(json)) {
                        errors.add("$where: @machine-json is not valid JSON: $json")
                    }
                }
                "guard-rule" -> {
                    val rule = a.pairs["rule"]
                    if (rule == null) {
                        errors.add("$where: @guard-rule requires rule=NAME")
                    } else if (rule !in ruleNames) {
                        errors.add("$where: @guard-rule names unimplemented rule '$rule'")
                    }
                    val test = a.pairs["test"]
                    if (test != null && test !in testClasses) {
                        errors.add("$where: @guard-rule names unknown test class $test")
                    }
                }
            }
        }

        val orphans = trail - referenced
        if (orphans.isNotEmpty()) {
            errors.add("Breadcrumbs never referenced by any annotation: ${orphans.sorted()}")
        }
        return errors
    }
}
