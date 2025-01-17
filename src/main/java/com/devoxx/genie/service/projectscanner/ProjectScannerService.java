package com.devoxx.genie.service.projectscanner;

import com.devoxx.genie.model.ScanContentResult;
import com.devoxx.genie.service.DevoxxGenieSettingsService;
import com.devoxx.genie.service.DevoxxGenieSettingsServiceProvider;
import com.devoxx.genie.ui.util.NotificationUtil;
import com.devoxx.genie.ui.util.WindowContextFormatterUtil;
import com.devoxx.genie.util.GitignoreParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileVisitor;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public class ProjectScannerService {

    private static final Logger LOG = Logger.getInstance(ProjectScannerService.class);

    private static final Encoding ENCODING = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
    public static final String GITIGNORE = ".gitignore";

    private GitignoreParser gitignoreParser;

    public static ProjectScannerService getInstance() {
        return ApplicationManager.getApplication().getService(ProjectScannerService.class);
    }

    /**
     * Scan the project from start directory and return the project source tree and file contents.
     *
     * @param project                the project
     * @param startDirectory         the start directory
     * @param windowContextMaxTokens the window context for the language model
     * @param isTokenCalculation     whether the scan is for token calculation
     * @return the project context
     */
    public CompletableFuture<ScanContentResult> scanProject(Project project,
                                                            VirtualFile startDirectory,
                                                            int windowContextMaxTokens,
                                                            boolean isTokenCalculation) {
        CompletableFuture<ScanContentResult> future = new CompletableFuture<>();
        ScanContentResult scanContentResult = new ScanContentResult();

        ReadAction.nonBlocking(() -> {
                StringBuilder result = new StringBuilder();
                result.append("Directory Structure:\n");
                StringBuilder fullContent;

                initGitignoreParser(project, startDirectory);

                if (startDirectory == null) {
                    fullContent = getContentFromModules(project, windowContextMaxTokens, result, scanContentResult);
                } else {
                    fullContent = processDirectory(project, startDirectory, result, scanContentResult, windowContextMaxTokens);
                }

                String content = isTokenCalculation ? fullContent.toString() :
                    truncateToTokens(project, fullContent.toString(), windowContextMaxTokens, isTokenCalculation);

                scanContentResult.setTokenCount(ENCODING.countTokens(content));

                scanContentResult.setContent(content);

                return scanContentResult;
            }).inSmartMode(project)
            .finishOnUiThread(ModalityState.defaultModalityState(), future::complete)
            .submit(AppExecutorUtil.getAppExecutorService());

        return future;
    }

    /**
     * Initialize the GitignoreParser with the .gitignore file from the project.
     * @param project the project
     * @param startDirectory the start directory
     */
    public void initGitignoreParser(Project project, VirtualFile startDirectory) {
        VirtualFile gitignoreFile;
        if (startDirectory != null) {
            gitignoreFile = startDirectory.findChild(GITIGNORE);
        } else {
            gitignoreFile = project.getBaseDir().findChild(GITIGNORE);
        }

        if (gitignoreFile != null && gitignoreFile.exists()) {
            try {
                gitignoreParser = new GitignoreParser(Paths.get(gitignoreFile.getPath()));
            } catch (IOException e) {
                LOG.error("Error initializing GitignoreParser: " + e.getMessage());
            }
        }
    }

    /**
     * Get the project content from all modules.
     *
     * @param project                the project
     * @param windowContextMaxTokens the window context for the language model
     * @param result                 the result
     * @return the full content
     */
    private StringBuilder getContentFromModules(Project project,
                                                int windowContextMaxTokens,
                                                StringBuilder result,
                                                ScanContentResult scanContentResult) {

        UniqueDirectoryScannerService uniqueDirectoryScanner = new UniqueDirectoryScannerService();

        // Collect all content roots from modules
        VirtualFile[] contentRootsFromAllModules =
            ProjectRootManager.getInstance(project).getContentRootsFromAllModules();

        // Add all content roots to the unique directory scanner
        Arrays.stream(contentRootsFromAllModules)
            .distinct()
            .forEach(uniqueDirectoryScanner::addDirectory);

        // Get the highest root directory and process the content
        return uniqueDirectoryScanner
            .getHighestCommonRoot()
            .map(highestCommonRoot -> processDirectory(project,
                highestCommonRoot,
                result,
                scanContentResult,
                windowContextMaxTokens))
            .orElseThrow();
    }

    /**
     * Scan the project and return the project source tree and file contents from the start directory.
     *
     * @param project        the project
     * @param startDirectory the start directory
     * @param result         the result
     * @return the full content
     */
    private @NotNull StringBuilder processDirectory(Project project,
                                                    VirtualFile startDirectory,
                                                    @NotNull StringBuilder result,
                                                    ScanContentResult scanContentResult,
                                                    int windowContextMaxTokens) {
        result.append(generateSourceTreeRecursive(startDirectory, 0));

        result.append("\n\nFile Contents:\n");

        ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);

        StringBuilder fullContent = new StringBuilder(result);
        AtomicInteger currentTokens = new AtomicInteger(0);

        walkThroughDirectory(startDirectory, fileIndex, fullContent, currentTokens, windowContextMaxTokens, scanContentResult);
        return fullContent;
    }

    /**
     * Walk through the project directory and append the file contents to the full content.
     *
     * @param directory   the selected directory
     * @param fileIndex   the project file index
     * @param fullContent the full content
     */
    private void walkThroughDirectory(VirtualFile directory,
                                      ProjectFileIndex fileIndex,
                                      StringBuilder fullContent,
                                      AtomicInteger currentTokens,
                                      int maxTokens,
                                      ScanContentResult scanContentResult) {
        VfsUtilCore.visitChildrenRecursively(directory, new VirtualFileVisitor<Void>() {
            @Override
            public boolean visitFile(@NotNull VirtualFile file) {
                if (shouldExcludeDirectory(file)) {
                    scanContentResult.incrementSkippedDirectoryCount();
                    return false;
                }

                if (fileIndex.isInContent(file) && !shouldExcludeFile(file) && shouldIncludeFile(file)) {
                    scanContentResult.incrementFileCount();

                    String header = "\n--- " + file.getPath() + " ---\n";
                    fullContent.append(header);

                    try {
                        String content = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
                        content = processFileContent(content);
                        fullContent.append(content).append("\n");

                        int tokens = ENCODING.countTokens(content);
                        currentTokens.addAndGet(tokens);

                        if (currentTokens.get() >= maxTokens) {
                            return false; // Stop scanning if token limit is reached
                        }
                    } catch (Exception e) {
                        String errorMsg = "Error reading file: " + e.getMessage() + "\n";
                        fullContent.append(errorMsg);
                    }
                } else {
                    scanContentResult.incrementSkippedFileCount();
                }
                return true;
            }
        });
    }

    /**
     * Truncate the project context to a maximum number of tokens.
     * If the project context exceeds the limit, truncate it and append a message.
     *
     * @param project            the project
     * @param text               the project context
     * @param windowContext      the model window context
     * @param isTokenCalculation whether the scan is for token calculation
     */
    private String truncateToTokens(Project project,
                                    String text,
                                    int windowContext,
                                    boolean isTokenCalculation) {
        NumberFormat formatter = NumberFormat.getInstance();
        IntArrayList tokens = ENCODING.encode(text);
        if (tokens.size() <= windowContext) {
            if (!isTokenCalculation) {
                NotificationUtil.sendNotification(project, "Added. Project context " +
                    WindowContextFormatterUtil.format(tokens.size(), "tokens"));
            }
            return text;
        }
        IntArrayList truncatedTokens = new IntArrayList(windowContext);
        for (int i = 0; i < windowContext; i++) {
            truncatedTokens.add(tokens.get(i));
        }

        if (!isTokenCalculation) {
            NotificationUtil.sendNotification(project, "Project context truncated due to token limit, was " +
                formatter.format(tokens.size()) + " tokens but limit is " + formatter.format(windowContext) + " tokens. " +
                "You can exclude directories or files in the settings page.");
        }
        String truncatedContent = ENCODING.decode(truncatedTokens);
        return isTokenCalculation ? truncatedContent : truncatedContent + "\n--- Project context truncated due to token limit ---\n";
    }

    /**
     * Generate a tree structure of the project source files recursively.
     *
     * @param virtualFile the virtual file/directory
     * @param depth the depth
     * @return the tree structure
     */
    public @NotNull String generateSourceTreeRecursive(VirtualFile virtualFile, int depth) {
        StringBuilder result = new StringBuilder();
        String indent = "  ".repeat(depth);

        boolean excludeFile = shouldExcludeFile(virtualFile);
        boolean excludeDirectory = shouldExcludeDirectory(virtualFile);
        if (excludeFile || excludeDirectory) {
            return "";
        }

        result.append(indent).append(virtualFile.getName()).append("/\n");

        for (VirtualFile child : virtualFile.getChildren()) {
            if (child.isDirectory()) {
                result.append(generateSourceTreeRecursive(child, depth + 1));
            } else if (shouldIncludeFile(child)) {
                result.append(indent).append("  ").append(child.getName()).append("\n");
            }
        }

        return result.toString();
    }

    /**
     * Check if the directory should be excluded from the project context.
     *
     * @param file the directory
     * @return true if the directory should be excluded, false otherwise
     */
    private boolean shouldExcludeDirectory(@NotNull VirtualFile file) {
        DevoxxGenieSettingsService settings = DevoxxGenieSettingsServiceProvider.getInstance();
        return file.isDirectory() &&
            (settings.getExcludedDirectories().contains(file.getName()) || shouldExcludeFile(file));
    }

    /**
     * Check if the file should be excluded from the project context.
     *
     * @param file the file
     * @return true if the file should be excluded, false otherwise
     */
    private boolean shouldExcludeFile(@NotNull VirtualFile file) {
        if (DevoxxGenieSettingsServiceProvider.getInstance().getUseGitIgnore()) {
            if (gitignoreParser != null) {
                Path path = Paths.get(file.getPath());
                return gitignoreParser.matches(path);
            }
        }
        return false;
    }

    /**
     * Check if the file should be included in the project context.
     *
     * @param file the file
     * @return true if the file should be included, false otherwise
     */
    private boolean shouldIncludeFile(@NotNull VirtualFile file) {
        DevoxxGenieSettingsService settings = DevoxxGenieSettingsServiceProvider.getInstance();
        String extension = file.getExtension();
        boolean includedByExtension = extension != null && settings.getIncludedFileExtensions().contains(extension.toLowerCase());
        return includedByExtension && !shouldExcludeFile(file);
    }

    /**
     * Process the file content.
     *
     * @param content the file content
     * @return the processed content
     */
    private String processFileContent(String content) {
        if (DevoxxGenieSettingsServiceProvider.getInstance().getExcludeJavaDoc()) {
            return removeJavadoc(content);
        }
        return content;
    }

    /**
     * Remove Javadoc comments from the content.
     *
     * @param content the content
     * @return the content without Javadoc
     */
    private @NotNull String removeJavadoc(String content) {
        // Remove block comments (which include Javadoc)
        content = content.replaceAll("/\\*{1,2}[\\s\\S]*?\\*/", "");
        // Remove single-line comments that start with '///'
        content = content.replaceAll("^\\s*///.*$", "");
        return content;
    }
}
