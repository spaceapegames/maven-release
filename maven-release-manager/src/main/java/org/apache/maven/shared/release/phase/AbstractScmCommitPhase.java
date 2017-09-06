package org.apache.maven.shared.release.phase;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.project.MavenProject;
import org.apache.maven.scm.ScmException;
import org.apache.maven.scm.ScmFileSet;
import org.apache.maven.scm.ScmVersion;
import org.apache.maven.scm.command.checkin.CheckInScmResult;
import org.apache.maven.scm.manager.NoSuchScmProviderException;
import org.apache.maven.scm.provider.ScmProvider;
import org.apache.maven.scm.repository.ScmRepository;
import org.apache.maven.scm.repository.ScmRepositoryException;
import org.apache.maven.shared.release.ReleaseExecutionException;
import org.apache.maven.shared.release.ReleaseFailureException;
import org.apache.maven.shared.release.ReleaseResult;
import org.apache.maven.shared.release.config.ReleaseDescriptor;
import org.apache.maven.shared.release.env.ReleaseEnvironment;
import org.apache.maven.shared.release.scm.ReleaseScmCommandException;
import org.apache.maven.shared.release.scm.ReleaseScmRepositoryException;
import org.apache.maven.shared.release.scm.ScmRepositoryConfigurator;
import org.apache.maven.shared.release.util.ReleaseUtil;
import org.codehaus.plexus.util.DirectoryScanner;

import java.io.File;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Holds the basic concept of committing changes to the current working copy.
 *
 * @author <a href="mailto:brett@apache.org">Brett Porter</a>
 * @author <a href="mailto:me@lcorneliussen.de">Lars Corneliussen</a>
 */
public abstract class AbstractScmCommitPhase
    extends AbstractReleasePhase
{
    protected boolean beforeBranchOrTag = false;

    protected boolean afterBranchOrTag = false;

    /**
     * Tool that gets a configured SCM repository from release configuration.
     */
    protected ScmRepositoryConfigurator scmRepositoryConfigurator;

    /**
     * The format for the commit message.
     */
    protected String messageFormat;

    public ReleaseResult execute( ReleaseDescriptor releaseDescriptor, ReleaseEnvironment releaseEnvironment,
                                  List<MavenProject> reactorProjects )
        throws ReleaseExecutionException, ReleaseFailureException
    {
        ReleaseResult relResult = new ReleaseResult();

        validateConfiguration( releaseDescriptor );

        runLogic( releaseDescriptor, releaseEnvironment, reactorProjects, relResult, false );

        relResult.setResultCode( ReleaseResult.SUCCESS );

        return relResult;
    }

    public ReleaseResult simulate( ReleaseDescriptor releaseDescriptor, ReleaseEnvironment releaseEnvironment,
                                   List<MavenProject> reactorProjects )
        throws ReleaseExecutionException, ReleaseFailureException
    {
        ReleaseResult result = new ReleaseResult();

        validateConfiguration( releaseDescriptor );

        runLogic( releaseDescriptor, releaseEnvironment, reactorProjects, result, true );

        result.setResultCode( ReleaseResult.SUCCESS );
        return result;
    }

    protected abstract void runLogic( ReleaseDescriptor releaseDescriptor, ReleaseEnvironment releaseEnvironment,
                                      List<MavenProject> reactorProjects, ReleaseResult result, boolean simulating )
        throws ReleaseScmCommandException, ReleaseExecutionException, ReleaseScmRepositoryException;

    protected void performCheckins( ReleaseDescriptor releaseDescriptor, ReleaseEnvironment releaseEnvironment,
                                    List<MavenProject> reactorProjects, String message )
        throws ReleaseScmRepositoryException, ReleaseExecutionException, ReleaseScmCommandException
    {

        getLogger().info( "Checking in modified POMs..." );

        ScmRepository repository;
        ScmProvider provider;
        try
        {
            repository = scmRepositoryConfigurator.getConfiguredRepository( releaseDescriptor,
                                                                            releaseEnvironment.getSettings() );

            repository.getProviderRepository().setPushChanges( releaseDescriptor.isPushChanges() );

            provider = scmRepositoryConfigurator.getRepositoryProvider( repository );
        }
        catch ( ScmRepositoryException e )
        {
            throw new ReleaseScmRepositoryException( e.getMessage(), e.getValidationMessages() );
        }
        catch ( NoSuchScmProviderException e )
        {
            throw new ReleaseExecutionException( "Unable to configure SCM repository: " + e.getMessage(), e );
        }

        if ( releaseDescriptor.isCommitByProject() )
        {
            for ( MavenProject project : reactorProjects )
            {
                List<File> pomFiles = createPomFiles( releaseDescriptor, project );
                List<File> additionalFiles = createAdditionalFiles( releaseDescriptor, project ) ;
                List<File> files = new ArrayList<File>() ;
                files.addAll( pomFiles );
                files.addAll( additionalFiles ) ;
                ScmFileSet fileSet = new ScmFileSet( project.getFile().getParentFile(), files );

                checkin( provider, repository, fileSet, releaseDescriptor, message );
            }
        }
        else
        {
            List<File> pomFiles = createPomFiles( releaseDescriptor, reactorProjects );
            List<File> additionalFiles = createAdditionalFiles( releaseDescriptor, reactorProjects ) ;
            List<File> files = new ArrayList<File>() ;
            files.addAll( pomFiles );
            files.addAll( additionalFiles ) ;
            ScmFileSet fileSet = new ScmFileSet( new File( releaseDescriptor.getWorkingDirectory() ), files );

            checkin( provider, repository, fileSet, releaseDescriptor, message );
        }
    }

    private void checkin( ScmProvider provider, ScmRepository repository, ScmFileSet fileSet,
                          ReleaseDescriptor releaseDescriptor, String message )
        throws ReleaseExecutionException, ReleaseScmCommandException
    {
        CheckInScmResult result;
        try
        {
            result = provider.checkIn( repository, fileSet, (ScmVersion) null, message );
        }
        catch ( ScmException e )
        {
            throw new ReleaseExecutionException( "An error is occurred in the checkin process: " + e.getMessage(), e );
        }

        if ( !result.isSuccess() )
        {
            throw new ReleaseScmCommandException( "Unable to commit files", result );
        }
        if ( releaseDescriptor.isRemoteTagging() )
        {
            releaseDescriptor.setScmReleasedPomRevision( result.getScmRevision() );
        }
    }

    protected void simulateCheckins( ReleaseDescriptor releaseDescriptor, List<MavenProject> reactorProjects,
                                     ReleaseResult result, String message )
    {
        Collection<File> pomFiles = createPomFiles( releaseDescriptor, reactorProjects );
        Collection<File> additionalFiles = createAdditionalFiles( releaseDescriptor, reactorProjects ) ;
        Collection<File> files = new ArrayList<File>() ;
        files.addAll( pomFiles ) ;
        files.addAll( additionalFiles ) ;
        logInfo( result, "Full run would be commit " + files.size() + " files with message: '" + message + "'" );
    }

    protected void validateConfiguration( ReleaseDescriptor releaseDescriptor )
        throws ReleaseFailureException
    {
        if ( releaseDescriptor.getScmReleaseLabel() == null )
        {
            throw new ReleaseFailureException( "A release label is required for committing" );
        }
    }

    protected String createMessage( ReleaseDescriptor releaseDescriptor )
    {
        return MessageFormat.format( releaseDescriptor.getScmCommentPrefix() + messageFormat,
                                     new Object[]{releaseDescriptor.getScmReleaseLabel()} );
    }

    protected static List<File> createPomFiles( ReleaseDescriptor releaseDescriptor, MavenProject project )
    {
        List<File> pomFiles = new ArrayList<File>();

        pomFiles.add( ReleaseUtil.getStandardPom( project ) );

        if ( releaseDescriptor.isGenerateReleasePoms() && !releaseDescriptor.isSuppressCommitBeforeTagOrBranch() )
        {
            pomFiles.add( ReleaseUtil.getReleasePom( project ) );
        }

        return pomFiles;
    }

    protected static List<File> createPomFiles( ReleaseDescriptor releaseDescriptor,
                                                List<MavenProject> reactorProjects )
    {
        List<File> pomFiles = new ArrayList<File>();
        for ( MavenProject project : reactorProjects )
        {
            pomFiles.addAll( createPomFiles( releaseDescriptor, project ) );
        }
        return pomFiles;
    }

    protected static List<File> createAdditionalFiles( ReleaseDescriptor releaseDescriptor, MavenProject project )
    {

        List<File> additionalFiles = new ArrayList<File>();
        if ( releaseDescriptor.getAdditionalCommittedIncludes() != null )
        {
            List<String> additionalIncludes = releaseDescriptor.getAdditionalCommittedIncludes();
            String[] additionals = new String[additionalIncludes.size()];
            for ( int i = 0; i < additionalIncludes.size() ; i++ )
            {
                additionals[i] = additionalIncludes.get( i ).replaceAll( "\\s", "" );
            }

            if ( additionals.length != 0 )
            {

                File baseDir = project.getBasedir() ;

                DirectoryScanner scanner = new DirectoryScanner();
                scanner.setBasedir( baseDir );
                scanner.setIncludes( additionals );

                scanner.scan();

                if ( scanner.getIncludedFiles() != null )
                {
                    for ( String file : scanner.getIncludedFiles() )
                    {
                        additionalFiles.add( new File( baseDir, file ) ) ;
                    }
                }

            }
        }

        return additionalFiles;
    }

    protected static List<File> createAdditionalFiles( ReleaseDescriptor releaseDescriptor,
                                                       List<MavenProject> reactorProjects )
    {
        List<File> additionalFiles = new ArrayList<File>();
        for ( MavenProject project : reactorProjects )
        {
            additionalFiles.addAll( createAdditionalFiles( releaseDescriptor, project ) );
        }
        return additionalFiles;
    }


}
