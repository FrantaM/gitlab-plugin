/*
 * The MIT License
 *
 * Copyright 2015 Franta Mejta
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.dabsquared.gitlabjenkins.mrsr.macro;

import java.io.IOException;

import org.jenkinsci.plugins.tokenmacro.DataBoundTokenMacro;
import org.jenkinsci.plugins.tokenmacro.MacroEvaluationException;

import com.dabsquared.gitlabjenkins.RunResult;

import hudson.model.AbstractBuild;
import hudson.model.Result;
import hudson.model.TaskListener;

/**
 * @author Franta Mejta
 * @sa.date 2015-05-04T16:03:50+0200
 */
public class OverallResultMacro extends DataBoundTokenMacro {

    @Override
    public boolean acceptsMacroName(final String macroName) {
        return macroName.equals("MR_RESULT_OVERALL");
    }

    @Override
    public String evaluate(final AbstractBuild<?, ?> context, final TaskListener listener, final String macroName)
            throws MacroEvaluationException, IOException, InterruptedException {
        final Result rs = RunResult.worstOf(context);
        assert rs != null;

        return rs.toExportedObject();
    }

}
