package uk.co.cacoethes.lazybones

import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import spock.lang.Specification

/**
 * The super class for all functional tests. It provides support for running lazybones
 * as an external process and retrieving its output.
 */
abstract class AbstractFunctionalSpec extends Specification {

    // It seems this needs to be protected for some reason, otherwise the tests
    // throw a MissingPropertyException.
    protected final Object _outputLock = new Object()

    protected processOutput = new StringBuilder()

    protected final baseWorkDir = new File(System.getProperty("lazybones.testWorkDir") ?:
        System.getProperty("user.dir") + "/build/testWork", "cmdWork")

    protected final env = [:]

    protected long commandTimeout = 10000

    /**
     * Runs a lazybones command. For example:
     *
     * <pre>runCommand(["create", "ratpack", "0.1", "ratapp"], baseWorkDir)</pre>
     *
     * It will look for lazybones in either the directory specified by the
     * {@code lazybones.installDir} system property, or {cwd}/build/install. It will
     * also pass in the current PATH environment variable to the lazybones process if
     * {@code env} doesn't contain such a variable itself.
     * @param cmdList The command line to test, as a list.
     * @param workDir The directory to run the command in. In other words, this becomes
     * the process's current working directory.
     * @param inputs A list of input strings to pass to the process if it needs them.
     * These could be simple "y" or "n" strings to answer such questions, or something
     * larger.
     * @return The exit code of the lazybones process.
     */
    int runCommand(List cmdList, File workDir, List inputs = []) {
        // Clear out results of previous executions
        FileUtils.deleteDirectory(workDir)
        resetOutput()
        workDir.mkdirs()

        def lazybonesInstallDir = System.getProperty("lazybones.installDir") ?:
                System.getProperty("user.dir") + "/build/install"
        def lzbExecutable = FilenameUtils.concat(lazybonesInstallDir, "bin/lazybones")
        if (windows) {
            lzbExecutable = FilenameUtils.separatorsToWindows(lzbExecutable + ".bat")
        }

        // The PATH environment is needed to find the `java` command.
        if (!env["PATH"]) {
            env["PATH"] = System.getenv("PATH")
        }

        def systemProps = System.properties.findAll {
            it.key.startsWith("lazybones.") && !(it.key in ["installDir", "testWorkDir"])
        }.collect {
            "-D" + it.key + "=" + it.value
        }

        if (systemProps) {
            env["JAVA_OPTS"] = (env["JAVA_OPTS"] ?: "") + " " + systemProps.join(' ')
        }

        // The execute() method expects the environment as a list of strings of
        // the form VAR=value.
        def envp = env.collect { key, value -> key + "=" + value }

        Process process = ([lzbExecutable, "--stacktrace"] + cmdList).execute(envp, workDir)

        if (inputs) {
            def newLine = System.getProperty("line.separator")
            def line = new StringBuilder()
            inputs.each { String item ->
                line << item << newLine
            }

            // We're deliberately using the platform encoding when converting
            // the string to bytes, since that's the encoding the terminal is
            // likely using when users manually enter text in answer to ask()
            // questions.
            process.outputStream.write(line.toString().bytes)
            process.outputStream.flush()
        }

        def stdoutThread = consumeProcessStream(process.inputStream)
        def stderrThread = consumeProcessStream(process.errorStream)
        process.waitForOrKill(commandTimeout)
        int exitCode = process.exitValue()

        // The process may finish before the consuming threads have finished, so
        // give them a chance to complete so that we have the command output in
        // the buffer.
        stdoutThread.join 1000
        stderrThread.join 1000
        println "Output from executing ${cmdList.join(' ')} (exit code: $exitCode)"
        println "---------------------"
        println output
        return exitCode
    }

    /**
     * Returns the text output (both stdout and stderr) of the last command
     * that was executed.
     */
    String getOutput() {
        return processOutput.toString()
    }

    /**
     * Clears the saved command output.
     */
    void resetOutput() {
        synchronized (this._outputLock) {
            processOutput = new StringBuilder()
        }
    }

    /**
     * Reads the current version of Lazybones from a properties file and
     * returns it as a string.
     */
    protected String readLazybonesVersion() {
        return System.getProperty("lzbtest.expected.version")
    }

    protected final boolean isWindows() { return System.getProperty("os.name")?.startsWith("Windows") }

    private Thread consumeProcessStream(final InputStream stream) {
        char[] buffer = new char[256]
        Thread.start {
            def reader = new InputStreamReader(stream)
            def charsRead = 0
            while (charsRead != -1) {
                charsRead = reader.read(buffer, 0, 256)
                if (charsRead > 0) {
                    synchronized (this._outputLock) {
                        processOutput.append(buffer, 0, charsRead)
                    }
                }
            }
        }
    }

    private void removeFromOutput(String line) {
        synchronized (this._outputLock) {
            def pos = processOutput.indexOf(line)
            if (pos != -1) {
                processOutput.delete(pos, pos + line.size() - 1)
            }
        }
    }
}
