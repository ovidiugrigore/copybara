/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.copybara.util;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.copybara.git.GitExecPath.resolveGitBinary;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.copybara.shell.Command;
import com.google.copybara.shell.CommandException;
import com.google.copybara.util.DiffUtil.DiffFile.Operation;
import com.google.copybara.util.console.AnsiColor;
import com.google.copybara.util.console.Console;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.annotation.CheckReturnValue;

/**
 * Diff utilities that are repository-agnostic.
 */
public class DiffUtil {

  private static final byte[] EMPTY_DIFF = new byte[]{};

  /**
   * Calculates the diff between two sibling directory trees.
   *
   * <p>Returns the diff as an encoding-independent {@code byte[]} that can be write to a file or
   * fed directly into {@link DiffUtil#patch}.
   */
  public static byte[] diff(Path one, Path other, boolean verbose, Map<String, String> environment)
      throws IOException, InsideGitDirException {
    return new FoldersDiff(verbose, environment)
        .run(one, other);
  }

  /**
   * Return the changed files without computing renames/copies.
   *
   * <p>Each file name is relative to one/other paths.
   */
  public static ImmutableList<DiffFile> diffFiles(Path one, Path other, boolean verbose,
      Map<String, String> environment) throws IOException, InsideGitDirException {
    String cmdResult = new String(new FoldersDiff(verbose, environment)
        .withZOption()
        .withNameStatus()
        .withNoRenames()
        .run(one, other), StandardCharsets.UTF_8);

    ImmutableList.Builder<DiffFile> result = ImmutableList.builder();
    for (Iterator<String> iterator = Splitter.on((char) 0).split(cmdResult).iterator();
        iterator.hasNext(); ) {
      String strOp = iterator.next();
      if (Strings.isNullOrEmpty(strOp)) {
        continue;
      }
      Operation op = DiffFile.OP_BY_CHAR.get(strOp);
      if (op == null) {
        throw new IllegalStateException(
            String.format("Unknown type '%s'. Text:\n%s", strOp, cmdResult));
      }
      String file = iterator.next();
      Preconditions.checkState(file.contains("/"));
      result.add(new DiffFile(file.substring(file.indexOf("/") + 1), op));
    }
    return result.build();
  }

  public static class DiffFile {

    private final String name;
    private final Operation operation;
    private static final ImmutableMap<String, Operation> OP_BY_CHAR =
        Maps.uniqueIndex(Iterators.forArray(Operation.values()), e -> e.charType);

    @VisibleForTesting
    public DiffFile(String name, Operation operation) {
      this.name = checkNotNull(name);
      this.operation = checkNotNull(operation);
    }

    public String getName() {
      return name;
    }

    public Operation getOperation() {
      return operation;
    }

    public enum Operation {
      ADD("A"),
      DELETE("D"),
      MODIFIED("M");

      private final String charType;

      Operation(String charType) {
        this.charType = charType;
      }
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("name", name)
          .add("operation", operation)
          .toString();
    }
  }

  /**
   * Execute git diff between two folders
   */
  private static class FoldersDiff {

    private final boolean nameStatus;
    private final boolean noRenames;
    private final boolean zOption;
    private final boolean noIndex;
    private final boolean verbose;
    private final Map<String, String> environment;

    private FoldersDiff(boolean verbose, Map<String, String> environment) {
      this.verbose = verbose;
      this.environment = environment;
      nameStatus = false;
      noRenames = false;
      zOption = false;
      noIndex = false;
    }

    private FoldersDiff(boolean verbose, Map<String, String> environment, boolean nameStatus,
        boolean noRenames, boolean zOption, boolean noIndex) {
      this.verbose = verbose;
      this.environment = environment;
      this.nameStatus = nameStatus;
      this.noRenames = noRenames;
      this.zOption = zOption;
      this.noIndex = noIndex;
    }

    @CheckReturnValue
    private FoldersDiff withNameStatus() {
      return new FoldersDiff(verbose, environment, /*nameStatus=*/true, noRenames, zOption,
          noIndex);
    }

    @CheckReturnValue
    private FoldersDiff withNoRenames() {
      return new FoldersDiff(verbose, environment, nameStatus, /*noRenames=*/true, zOption,
          noIndex);
    }

    @CheckReturnValue
    private FoldersDiff withZOption() {
      return new FoldersDiff(verbose, environment, nameStatus, noRenames, /*zOption=*/true,
          noIndex);
    }

    // TODO(malcon): Use this instead of checkNotInsideGitRepo
    @CheckReturnValue
    private FoldersDiff withNoIndex() {
      return new FoldersDiff(verbose, environment, nameStatus, noRenames, zOption, /*noIndex=*/
          true);
    }

    private byte[] run(Path one, Path other) throws IOException, InsideGitDirException {
      Preconditions.checkArgument(one.getParent().equals(other.getParent()),
          "Paths 'one' and 'other' must be sibling directories.");
      checkNotInsideGitRepo(one, verbose, environment);
      Path root = one.getParent();
      List<String> params = Lists.newArrayList("git", "diff", "--no-color");
      if (nameStatus) {
        params.add("--name-status");
      }
      if (noRenames) {
        params.add("--no-renames");
      }
      if (zOption) {
        params.add("-z");
      }
      params.add("--");
      params.add(root.relativize(one).toString());
      params.add(root.relativize(other).toString());
      Command cmd = new Command(params.toArray(new String[]{}), environment, root.toFile());
      try {
        new CommandRunner(cmd)
            .withVerbose(verbose)
            .execute();
        return EMPTY_DIFF;
      } catch (BadExitStatusWithOutputException e) {
        CommandOutput output = e.getOutput();
        // git diff returns exit status 0 when contents are identical, or 1 when they are different
        if (!Strings.isNullOrEmpty(output.getStderr())) {
          throw new IOException(String.format(
              "Error executing 'git diff': %s. Stderr: \n%s", e.getMessage(), output.getStderr()),
              e);
        }
        return output.getStdoutBytes();
      } catch (CommandException e) {
        throw new IOException("Error executing 'git diff'", e);
      }

    }
  }

  /**
   * Given a git compatible diff, returns the diff colorized if the console allows it.
   */
  public static String colorize(Console console, String diffText) {
    StringBuilder sb = new StringBuilder();
    for (String line : Splitter.on("\n").split(diffText)) {
      sb.append("\n");
      if (line.startsWith("+")) {
        sb.append(console.colorize(AnsiColor.GREEN, line));
      } else if (line.startsWith("-")) {
        sb.append(console.colorize(AnsiColor.RED, line));
      } else {
        sb.append(line);
      }
    }
    return sb.toString();
  }

  /**
   * It is very common for users to have a git repo for their $HOME, so that they can version
   * their configurations. Unfortunately this fails for the default output directory (inside
   * $HOME).
   */
  public static void checkNotInsideGitRepo(Path path, boolean verbose, Map<String, String> env)
      throws IOException, InsideGitDirException {
    try {
      Command cmd = new Command(new String[]{
          resolveGitBinary(env), "rev-parse", "--git-dir"}, env, path.toFile());

      String gitDir = new CommandRunner(cmd)
          .withVerbose(verbose)
          .execute()
          .getStdout()
          .trim();

      // If it doesn't fail it means taht we are inside a git directory
      throw new InsideGitDirException(String.format(
          "Cannot diff/patch because Copybara temporary directory (%s) is inside a git"
              + " directory (%s).", path, gitDir),
          gitDir, path);
    } catch (BadExitStatusWithOutputException e) {
      // Some git versions return "Not", others "not"
      if (!e.getOutput().getStderr().contains(/*N*/"ot a git repository")) {
        throw new IOException("Error executing rev-parse", e);
      }
    } catch (CommandException e) {
      throw new IOException("Error executing rev-parse", e);
    }
  }
}
