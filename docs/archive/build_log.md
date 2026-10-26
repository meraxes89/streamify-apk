# CI/CD Build Logs Workflow 🛠️

This document outlines how the GitHub Actions CI pipeline handles build failures and where to find the corresponding error logs. **Read this before attempting to reproduce a build failure locally.**

## Where are the Build Logs?

When the GitHub Actions workflow (`.github/workflows/build.yml`) encounters a build failure, it **automatically extracts the Gradle error logs and pushes them to a dedicated branch** in this repository.

*   **Branch:** `build-logs`
*   **File Format:** `build-{RUN_NUMBER}.log` (e.g., `build-72.log`)

## How the CI Pipeline Works

1.  **Trigger:** A push or pull request to the `main` or `develop` branch triggers the GitHub Action.
2.  **Compilation:** The runner executes `./gradlew clean assembleDebug --no-build-cache --stacktrace`. Output is piped to a temporary `build.log` file.
3.  **Failure Catching:** If the Gradle command exits with a non-zero status code, an environment variable `BUILD_FAILED=true` is set.
4.  **Log Extraction & Storage:**
    *   The workflow parses the temporary `build.log` using `grep` to extract the exact compilation errors.
    *   It posts these errors as a comment on the corresponding commit.
    *   It then checks out the `build-logs` branch, copies the `build.log` to `build-${GITHUB_RUN_NUMBER}.log`, commits the file, and pushes it back to the repository.

## Instructions for AI Agents / Developers

If the user reports a build failure, **do not immediately run the build locally**. Instead, follow these steps to instantly fetch the CI logs:

1.  **Fetch the Logs Branch:**
    ```bash
    git fetch origin build-logs
    git checkout origin/build-logs
    ```
2.  **Find the Latest Log:**
    Locate the most recent `build-*.log` file in the root directory.
    ```bash
    ls -t build-*.log | head -n 1
    ```
3.  **Read and Resolve:**
    Read the contents of the latest log file to identify the error (e.g., Unresolved references, missing dependencies, or C++ compilation errors).
4.  **Switch Back and Fix:**
    ```bash
    git checkout main
    ```
    Implement the fix, commit, and push.

By following this flow, you save significant time and compute resources by avoiding redundant local builds.
