package dev.gitcommitforai.git;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diff.impl.patch.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.*;
import com.intellij.openapi.vcs.changes.*;
import com.intellij.openapi.vcs.impl.PartialChangesUtil;
import com.intellij.openapi.vfs.*;
import dev.gitcommitforai.core.*;
import git4idea.repo.GitRepositoryManager;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.*;

public final class CommitContextCollector {
    public record Context(String diff, String project, String branch, int included, int excluded) {}
    public Context collect(Project project, CommitSelection selection, SettingsState settings, ProgressIndicator progress) throws Exception {
        PathRules rules = new PathRules(settings.excludeFiles ? settings.excludePatterns : "");
        List<Change> changes = ReadAction.compute(() -> PartialChangesUtil.wrapPartialChanges(project, selection.changes()));
        String base = Objects.toString(project.getBasePath(), "");
        StringBuilder diff = new StringBuilder();
        int included = 0, excluded = 0, totalBudget = Math.min(800000, settings.maxPromptChars * 3);
        for (Change change : changes) {
            progress.checkCanceled();
            FilePath path = ChangesUtil.getFilePath(change);
            String relative = relative(base, path.getPath());
            if (rules.excludes(relative)) { excluded++; continue; }
            progress.setText("读取选中变更：" + relative);
            VirtualFile file = path.getVirtualFile();
            if ((change.getBeforeRevision() != null && IdeaTextPatchBuilder.isBinaryRevision(change.getBeforeRevision())) || (change.getAfterRevision() != null && IdeaTextPatchBuilder.isBinaryRevision(change.getAfterRevision()))) {
                append(diff, "File: " + relative + "\n[二进制变更，未发送文件内容]\n", totalBudget); included++; continue;
            }
            if (file != null && file.getLength() > 1024 * 1024) {
                append(diff, "File: " + relative + "\n[文件超过 1 MiB，省略内容]\n", totalBudget); included++; continue;
            }
            List<FilePatch> patches = IdeaTextPatchBuilder.buildPatch(project, List.of(change), Path.of(base), false, false, progress::checkCanceled);
            StringWriter writer = new StringWriter();
            UnifiedDiffWriter.write(project, patches, writer, "\n", null);
            String patch = writer.toString();
            if (!patch.isBlank()) { append(diff, PromptEngine.truncate(patch, Math.max(3000, Math.min(12000, settings.maxPromptChars / 2)), "\n[此文件后续 diff 已省略]\n"), totalBudget); included++; }
        }
        for (FilePath path : selection.unversioned()) {
            progress.checkCanceled();
            String relative = relative(base, path.getPath());
            if (rules.excludes(relative)) { excluded++; continue; }
            VirtualFile file = path.getVirtualFile();
            if (file == null || file.isDirectory() || file.getFileType().isBinary()) { excluded++; continue; }
            progress.setText("读取新文件：" + relative);
            String content;
            if (file.getLength() > 256 * 1024) content = "[新文件超过 256 KiB，省略内容]";
            else content = ReadAction.compute(() -> {
                var document = FileDocumentManager.getInstance().getCachedDocument(file);
                if (document != null) return document.getText();
                try { return VfsUtilCore.loadText(file); } catch (java.io.IOException e) { throw new IllegalArgumentException("无法读取新文件：" + relative, e); }
            });
            append(diff, "New file: " + relative + "\n" + PromptEngine.truncate(content, 6000, "\n[此文件后续内容已省略]") + "\n", totalBudget); included++;
        }
        if (included == 0 || diff.isEmpty()) throw new IllegalArgumentException("没有可用于生成的变更。请勾选文件，并检查文件排除规则。");
        String branch = ReadAction.compute(() -> GitRepositoryManager.getInstance(project).getRepositories().stream()
            .map(r -> r.getRoot().getName() + ":" + Objects.toString(r.getCurrentBranchName(), "detached HEAD")).distinct().reduce((a,b) -> a + ", " + b).orElse(""));
        String prefix = "Selected files: " + included + "; excluded files: " + excluded + "\n\n";
        return new Context(prefix + diff, project.getName(), PromptEngine.truncate(branch, 1000, "…"), included, excluded);
    }
    private static void append(StringBuilder target, String text, int limit) {
        int remaining = limit - target.length();
        if (remaining > 0) target.append(PromptEngine.truncate(text, remaining, "\n[后续变更因输入预算省略]"));
    }
    private static String relative(String base, String path) {
        try { return Path.of(base).relativize(Path.of(path)).toString().replace('\\', '/'); }
        catch (Exception e) { return path.replace('\\', '/'); }
    }
}

