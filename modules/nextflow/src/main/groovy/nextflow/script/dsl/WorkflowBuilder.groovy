/*
 * Copyright 2013-2023, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nextflow.script.dsl

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.script.BaseScript
import nextflow.script.BodyDef
import nextflow.script.WorkflowDef
/**
 * Implements the workflow builder DSL.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@Slf4j
@CompileStatic
class WorkflowBuilder {

    static final private String TAKE_PREFIX = '_take_'
    static final private String EMIT_PREFIX = '_emit_'

    private BaseScript owner
    private String name
    private BodyDef body
    private Map<String,Object> takes = new LinkedHashMap<>(10)
    private Map<String,Object> emits = new LinkedHashMap<>(10)

    WorkflowBuilder(BaseScript owner, String name=null) {
        this.owner = owner
        this.name = name
    }

    @Override
    def invokeMethod(String name, Object args) {
        if( name.startsWith(TAKE_PREFIX) )
            takes.put(name.substring(TAKE_PREFIX.size()), args)

        else if( name.startsWith(EMIT_PREFIX) )
            emits.put(name.substring(EMIT_PREFIX.size()), args)

        else
            throw new MissingMethodException(name, WorkflowDef, args)
    }

    WorkflowBuilder withBody(BodyDef body) {
        this.body = body
        return this
    }

    WorkflowDef build() {
        new WorkflowDef(
            owner,
            name,
            body,
            new ArrayList<>(takes.keySet()),
            new ArrayList<>(emits.keySet())
        )
    }
}
