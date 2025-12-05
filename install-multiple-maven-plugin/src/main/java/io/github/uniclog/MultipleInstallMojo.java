package io.github.uniclog;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.aether.DefaultRepositoryCache;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.installation.InstallRequest;
import org.eclipse.aether.installation.InstallationException;
import org.eclipse.aether.repository.LocalRepository;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

import static java.util.Objects.isNull;

@Mojo(name = "install-multiple")
public class MultipleInstallMojo extends AbstractMojo {
    private static final Pattern POM_ENTRY_PATTERN = Pattern.compile("META-INF/maven/.*/pom\\.xml");
    private static final Predicate<JarEntry> IS_POM_ENTRY = entry -> POM_ENTRY_PATTERN.matcher(entry.getName()).matches();

    @Parameter(property = "files", required = true)
    private File files;
    @Parameter(property = "localRepositoryPath", defaultValue = "${project.build.directory}/local_repo")
    private File localRepositoryPath;
    @Parameter(defaultValue = "${session}", required = true, readonly = true)
    private MavenSession session;
    @Component
    private RepositorySystem repositorySystem;

    @Override
    public void execute() throws MojoExecutionException {
        File[] jarFiles = files.listFiles((dir, name) -> name.endsWith(".jar"));
        if (isNull(jarFiles) || jarFiles.length == 0) {
            getLog().warn("Artifacts not found");
            return;
        }
        getLog().debug("Jars:" + Arrays.toString(jarFiles));
        for (File file : jarFiles) {
            installJar(file);
        }
    }

    private void installJar(File file) throws MojoExecutionException {
        File pomFilePath = readingPomFromJarFile(file);
        if (isNull(pomFilePath)) {
            getLog().warn("POM file not found in JAR: " + file.getAbsolutePath());
            return;
        }
        Model model = readModel(pomFilePath);
        processModel(model);
        Artifact mainArtifact = new DefaultArtifact(
                model.getGroupId(),
                model.getArtifactId(),
                "jar",
                model.getVersion()
        ).setFile(file);
        InstallRequest installRequest = new InstallRequest().addArtifact(mainArtifact);
        var repositorySystemSession = getDefaultRepositorySystemSession();
        try {
            repositorySystem.install(repositorySystemSession, installRequest);
        } catch (InstallationException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    private DefaultRepositorySystemSession getDefaultRepositorySystemSession() {
        var repositorySystemSession = new DefaultRepositorySystemSession(session.getRepositorySession());
        repositorySystemSession.setCache(new DefaultRepositoryCache());
        String contentType = repositorySystemSession.getLocalRepository().getContentType();
        if ("enhanced".equals(contentType)) {
            contentType = "default";
        }
        var localRepository = new LocalRepository(localRepositoryPath, contentType);
        var localRepositoryManager = repositorySystem.newLocalRepositoryManager(repositorySystemSession, localRepository);
        repositorySystemSession.setLocalRepositoryManager(localRepositoryManager);
        return repositorySystemSession;
    }

    private File readingPomFromJarFile(File file) {
        String base = file.getName();
        if (base.contains(".")) {
            base = base.substring(0, base.lastIndexOf('.'));
        }
        try (JarFile jarFile = new JarFile(file)) {
            JarEntry pomEntry = jarFile.stream().filter(IS_POM_ENTRY).findAny().orElse(null);
            if (isNull(pomEntry)) {
                getLog().warn("pom.xml not found in " + file.getName());
                return null;
            }
            Path tempPomFile = Files.createTempFile(base, ".pom");
            Files.copy(jarFile.getInputStream(pomEntry), tempPomFile, StandardCopyOption.REPLACE_EXISTING);
            getLog().debug("Loading " + pomEntry.getName());
            return tempPomFile.toFile();
        } catch (IOException e) {
            return null;
        }
    }

    private Model readModel(File pomFile) throws MojoExecutionException {
        try (InputStream reader = Files.newInputStream(pomFile.toPath())) {
            return new MavenXpp3Reader().read(reader);
        } catch (FileNotFoundException e) {
            throw new MojoExecutionException("File not found " + pomFile, e);
        } catch (IOException e) {
            throw new MojoExecutionException("Error reading POM " + pomFile, e);
        } catch (XmlPullParserException e) {
            throw new MojoExecutionException("Error parsing POM " + pomFile, e);
        }
    }

    private void processModel(Model model) throws MojoExecutionException {
        Parent parent = model.getParent();
        if (model.getGroupId() == null && parent != null) {
            model.setGroupId(parent.getGroupId());
        }
        if (model.getVersion() == null && parent != null) {
            model.setVersion(parent.getVersion());
        }
        if (isNull(model.getGroupId()) || isNull(model.getArtifactId()) || isNull(model.getVersion()) || isNull(model.getPackaging())) {
            throw new MojoExecutionException(
                    "The artifact information is incomplete: 'groupId', 'artifactId', 'version', 'packaging' are required.");
        }
    }
}