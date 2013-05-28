/*
 * Copyright (c) 2012, the authors.
 *
 *   This file is part of 'Nextflow'.
 *
 *   Nextflow is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Nextflow is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Nextflow.  If not, see <http://www.gnu.org/licenses/>.
 */

package nextflow.processor
import groovy.transform.InheritConstructors
import groovy.util.logging.Slf4j
import nextflow.util.ByteDumper
import nextflow.util.Duration
import org.apache.commons.io.IOUtils
import org.codehaus.groovy.runtime.IOGroovyMethods

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@InheritConstructors
class LocalTaskProcessor extends AbstractTaskProcessor {

    private static final COMMAND_OUT_FILENAME = '.command.out'

    private static final COMMAND_RUNNER_FILENAME = '.command.run'

    private static final COMMAND_ENV_FILENAME = '.command.env'

    private static final COMMAND_SCRIPT_FILENAME = '.command.sh'

    private Duration maxDuration

    /**
     * The max duration time allowed for the job to be executed, this value sets the '-l h_rt' squb command line option.
     *
     * @param duration0 The max allowed time expressed as duration string, Accepted units are 'min', 'hour', 'day'.
     *                  For example {@code maxDuration '30 min'}, {@code maxDuration '10 hour'}, {@code maxDuration '2 day'}
     */
    LocalTaskProcessor maxDuration( String duration0 ) {
        this.maxDuration = new Duration(duration0)
        return this
    }


    /**
     * Run a system executable script
     *
     * @param script
     * @return
     */
    protected void launchTask( TaskRun task )  {
        assert task
        assert task.@script
        assert task.workDirectory

        final scratch = task.workDirectory
        log.debug "Lauching task > ${task.name} -- scratch folder: $scratch"

        /*
         * save the environment to a file
         */
        final envMap = getProcessEnvironment()
        final envBuilder = new StringBuilder()
        envMap.each { name, value ->
            if( name ==~ /[a-zA-Z_]+[a-zA-Z0-9_]*/ ) {
                envBuilder << "export $name='$value'" << '\n'
            }
            else {
                log.debug "Task ${task.name} > Invalid environment variable name: '${name}'"
            }
        }
        new File(scratch, COMMAND_ENV_FILENAME).text = envBuilder.toString()


        /*
         * save the main script file
         */
        def scriptFile = new File(scratch, COMMAND_SCRIPT_FILENAME)
        scriptFile.text = normalizeScript(task.script.toString())
        scriptFile.setExecutable(true)

        /*
         * create the runner script which will launch the script
         */
        def runnerText = """
                    source ${COMMAND_ENV_FILENAME}
                    ./${COMMAND_SCRIPT_FILENAME}
                    """
        def runnerFile = new File(scratch, COMMAND_RUNNER_FILENAME)
        runnerFile.text = normalizeScript(runnerText)
        runnerFile.setExecutable(true)

        /*
         * save the reference to the scriptFile
         */
        task.script = scriptFile

        ProcessBuilder builder = new ProcessBuilder()
                .directory(scratch)
                .command(runnerFile.absolutePath)
                .redirectErrorStream(true)

        // -- start the execution and notify the event to the monitor
        Process process = builder.start()
        task.status = TaskRun.Status.RUNNING

        // -- copy the input value to the process standard input
        if( task.input != null ) {
            pipeTaskInput( task, process )
        }

        File fileOut = new File(scratch, COMMAND_OUT_FILENAME)
        ByteDumper dumper = null
        try {
            // -- print the process out if it is not capture by the output
            //    * The byte dumper uses a separate thread to capture the process stdout
            //    * The process stdout is captured in two condition:
            //      when the flag 'echo' is set or when it goes in the output channel (outputs['-'])
            //
            BufferedOutputStream streamOut = new BufferedOutputStream( new FileOutputStream(fileOut) )

            def handler = { byte[] data, int len ->
                streamOut.write(data,0,len)
                if( echo ) System.out.print(new String(data,0,len))
            }
            dumper = new ByteDumper(process.getInputStream(), handler)
            dumper.setName("dumper-$name")
            dumper.start()

            // -- wait the the process completes
            if( maxDuration ) {
                log.debug "Running task > ${task.name} -- waiting max: ${maxDuration}"
                process.waitForOrKill(maxDuration.toMillis())
                task.exitCode = process.exitValue()
            }
            else {
                log.debug "Running task > ${task.name} -- wait forever"
                task.exitCode = process.waitFor()
            }

            log.debug "Task completed > ${task.name} -- exit code: ${task.exitCode}; success: ${task.exitCode in validExitCodes}"

            dumper?.await(500)
            streamOut.close()

        }
        finally {

            dumper?.terminate()
            IOUtils.closeQuietly(process.in)
            IOUtils.closeQuietly(process.out)
            IOUtils.closeQuietly(process.err)
            process.destroy()

            task.output = fileOut
        }
    }

    protected getStdOutFile( TaskRun task ) {

        new File(task.workDirectory, COMMAND_OUT_FILENAME)

    }


    /**
     * Pipe the {@code TaskDef#input} to the {@code Process}
     *
     * @param task The current task to be executed
     * @param process The system process that will run the task
     */
    protected void pipeTaskInput( TaskRun task, Process process ) {

        Thread.start {
            try {
                IOGroovyMethods.withStream(new BufferedOutputStream(process.getOutputStream())) {writer -> writer << task.input}
            }
            catch( Exception e ) {
                log.warn "Unable to pipe input data for task: ${task.name}"
            }
        }
    }

}
