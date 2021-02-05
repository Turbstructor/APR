package org.apache.maven.continuum.execution.maven.m2;

/*
 * Copyright 2004-2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.maven.continuum.model.project.Project;
import org.apache.maven.continuum.project.builder.ContinuumProjectBuildingResult;
import org.apache.maven.project.MavenProject;
import org.apache.maven.artifact.repository.ArtifactRepository;

import java.io.File;

/**
 * @author <a href="mailto:jason@maven.org">Jason van Zyl</a>
 * @version $Id$
 */
public interface MavenBuilderHelper
{
    String ROLE = MavenBuilderHelper.class.getName();

    void mapMetadataToProject( File metadata, Project project )
        throws MavenBuilderHelperException;

    /**
     * @deprecated use {@link #getMavenProject(ContinuumProjectBuildingResult, File)} instead.
     */
    MavenProject getMavenProject( File file )
        throws MavenBuilderHelperException;

    MavenProject getMavenProject( ContinuumProjectBuildingResult result, File file );

    /**
     * @deprecated use {@link #mapMavenProjectToContinuumProject(ContinuumProjectBuildingResult, MavenProject, Project)} instead.
     */
    void mapMavenProjectToContinuumProject( MavenProject mavenProject, Project continuumProject )
        throws MavenBuilderHelperException;

    void mapMavenProjectToContinuumProject( ContinuumProjectBuildingResult result, MavenProject mavenProject,
                                            Project continuumProject );

    ArtifactRepository getLocalRepository();
}