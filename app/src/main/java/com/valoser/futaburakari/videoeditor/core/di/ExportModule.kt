package com.valoser.futaburakari.videoeditor.core.di

import com.valoser.futaburakari.videoeditor.domain.usecase.ExportVideoUseCase
import com.valoser.futaburakari.videoeditor.data.usecase.ExportVideoUseCaseImpl
import com.valoser.futaburakari.videoeditor.export.ExportPipeline
import com.valoser.futaburakari.videoeditor.export.ExportPipelineImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ExportModule {

    @Binds
    @Singleton
    abstract fun bindExportVideoUseCase(
        useCase: ExportVideoUseCaseImpl
    ): ExportVideoUseCase

    @Binds
    @Singleton
    abstract fun bindExportPipeline(
        pipeline: ExportPipelineImpl
    ): ExportPipeline
}
