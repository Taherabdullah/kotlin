/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.tools.projectWizard.moduleConfigurators

import org.jetbrains.kotlin.tools.projectWizard.Versions
import org.jetbrains.kotlin.tools.projectWizard.core.context.ReadingContext
import org.jetbrains.kotlin.tools.projectWizard.core.context.WritingContext
import org.jetbrains.kotlin.tools.projectWizard.core.*
import org.jetbrains.kotlin.tools.projectWizard.core.entity.PluginSettingReference
import org.jetbrains.kotlin.tools.projectWizard.core.entity.SettingType
import org.jetbrains.kotlin.tools.projectWizard.core.entity.reference
import org.jetbrains.kotlin.tools.projectWizard.ir.buildsystem.gradle.AndroidConfigIR
import org.jetbrains.kotlin.tools.projectWizard.plugins.AndroidPlugin
import org.jetbrains.kotlin.tools.projectWizard.plugins.kotlin.ModuleConfigurationData
import org.jetbrains.kotlin.tools.projectWizard.plugins.kotlin.ModuleType
import org.jetbrains.kotlin.tools.projectWizard.settings.buildsystem.Module
import org.jetbrains.kotlin.tools.projectWizard.settings.javaPackage
import org.jetbrains.kotlin.tools.projectWizard.core.entity.ModuleConfiguratorSetting
import org.jetbrains.kotlin.tools.projectWizard.core.service.kotlinVersionKind
import org.jetbrains.kotlin.tools.projectWizard.ir.buildsystem.*
import org.jetbrains.kotlin.tools.projectWizard.library.MavenArtifact
import org.jetbrains.kotlin.tools.projectWizard.phases.GenerationPhase
import org.jetbrains.kotlin.tools.projectWizard.plugins.kotlin.KotlinPlugin
import org.jetbrains.kotlin.tools.projectWizard.plugins.kotlin.ModuleSubType
import org.jetbrains.kotlin.tools.projectWizard.plugins.templates.TemplatesPlugin
import org.jetbrains.kotlin.tools.projectWizard.settings.DisplayableSettingItem
import org.jetbrains.kotlin.tools.projectWizard.settings.JavaPackage
import org.jetbrains.kotlin.tools.projectWizard.settings.buildsystem.DefaultRepository
import org.jetbrains.kotlin.tools.projectWizard.settings.buildsystem.Repository
import org.jetbrains.kotlin.tools.projectWizard.settings.version.Version
import org.jetbrains.kotlin.tools.projectWizard.templates.FileTemplate
import org.jetbrains.kotlin.tools.projectWizard.templates.FileTemplateDescriptor
import java.nio.file.Path

interface AndroidModuleConfigurator : ModuleConfigurator,
    ModuleConfiguratorWithSettings,
    ModuleConfiguratorWithModuleType,
    GradleModuleConfigurator {

    override val moduleType: ModuleType
        get() = ModuleType.jvm
    override val greyText: String
        get() = "Requires Android SDK"

    override fun getPluginSettings(): List<PluginSettingReference<Any, SettingType<Any>>> =
        listOf(AndroidPlugin::androidSdkPath.reference)

    override fun createBuildFileIRs(
        readingContext: ReadingContext,
        configurationData: ModuleConfigurationData,
        module: Module
    ) = buildList<BuildSystemIR> {
        +GradleOnlyPluginByNameIR(readingContext.createAndroidPlugin(module).pluginName)

        +GradleOnlyPluginByNameIR("kotlin-android-extensions")
        +AndroidConfigIR(
            when (readingContext.createAndroidPlugin(module)) {
                AndroidGradlePlugin.APPLICATION -> module.javaPackage(configurationData.pomIr)
                AndroidGradlePlugin.LIBRARY -> null
            }
        )
        +createRepositories(configurationData.kotlinVersion).map(::RepositoryIR)
    }

    fun ReadingContext.createAndroidPlugin(module: Module): AndroidGradlePlugin

    override fun ReadingContext.createSettingsGradleIRs(module: Module) = buildList<BuildSystemIR> {
        +createRepositories(KotlinPlugin::version.propertyValue).map { PluginManagementRepositoryIR(RepositoryIR(it)) }
        +AndroidResolutionStrategyIR(Versions.GradlePlugins.ANDROID)
    }

    override fun createModuleIRs(
        readingContext: ReadingContext,
        configurationData: ModuleConfigurationData,
        module: Module
    ): List<BuildSystemIR> =
        buildList {
            +ArtifactBasedLibraryDependencyIR(
                MavenArtifact(DefaultRepository.GOOGLE, "androidx.core", "core-ktx"),
                version = Version.fromString("1.1.0"),
                dependencyType = DependencyType.MAIN
            )
        }


    override fun createStdlibType(configurationData: ModuleConfigurationData, module: Module): StdlibType? =
        StdlibType.StdlibJdk7

    object FileTemplateDescriptors {
        val activityMainXml = FileTemplateDescriptor(
            "android/activity_main.xml.vm",
            "src" / "main" / "res" / "layout" / "activity_main.xml"
        )

        val androidManifestXml = FileTemplateDescriptor(
            "android/AndroidManifest.xml.vm",
            "src" / "main" / "AndroidManifest.xml"
        )

        val androidanifestForLibraryXml = FileTemplateDescriptor(
            "android/AndroidManifestLibrary.xml.vm",
            "src" / "main" / "AndroidManifest.xml"
        )

        fun mainActivityKt(javaPackage: JavaPackage) = FileTemplateDescriptor(
            "android/MainActivity.kt.vm",
            "src" / "main" / "java" / javaPackage.asPath() / "MainActivity.kt"
        )
    }

    companion object {
        fun createRepositories(kotlinVersion: Version) = buildList<Repository> {
            +DefaultRepository.GRADLE_PLUGIN_PORTAL
            +DefaultRepository.GOOGLE
            +DefaultRepository.JCENTER
            addIfNotNull(kotlinVersion.kotlinVersionKind.repository)
        }
    }
}

object AndroidTargetConfigurator : TargetConfigurator,
    SimpleTargetConfigurator,
    AndroidModuleConfigurator,
    SingleCoexistenceTargetConfigurator,
    ModuleConfiguratorSettings() {
    override val moduleSubType = ModuleSubType.android
    override val moduleType = ModuleType.jvm

    override fun ReadingContext.createAndroidPlugin(module: Module): AndroidGradlePlugin =
        withSettingsOf(module) { androidPlugin.reference.settingValue }

    override fun getConfiguratorSettings() = buildList<ModuleConfiguratorSetting<*, *>> {
        +super.getConfiguratorSettings()
        +androidPlugin
    }

    override fun WritingContext.runArbitraryTask(
        configurationData: ModuleConfigurationData,
        module: Module,
        modulePath: Path
    ): TaskResult<Unit> = computeM {
        val javaPackage = module.javaPackage(configurationData.pomIr)
        val settings = mapOf("package" to javaPackage)
        TemplatesPlugin::addFileTemplates.execute(
            listOf(
                FileTemplate(AndroidModuleConfigurator.FileTemplateDescriptors.androidanifestForLibraryXml, modulePath, settings)
            )
        )
    }

    val androidPlugin by enumSetting<AndroidGradlePlugin>("Android Library", neededAtPhase = GenerationPhase.PROJECT_GENERATION)
}

enum class AndroidGradlePlugin(override val text: String, val pluginName: String) : DisplayableSettingItem {
    APPLICATION("Android Application", "com.android.application"),
    LIBRARY("Android Library", "com.android.library")
}