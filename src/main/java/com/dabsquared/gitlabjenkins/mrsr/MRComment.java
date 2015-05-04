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
package com.dabsquared.gitlabjenkins.mrsr;

import javax.annotation.Nonnull;

import org.kohsuke.stapler.DataBoundConstructor;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Result;
import hudson.util.ListBoxModel;

/**
 * Definition of merge request comment.
 *
 * @author Franta Mejta
 * @sa.date 2015-05-04T13:42:18+0200
 */
@RequiredArgsConstructor(onConstructor = @__(@DataBoundConstructor))
public class MRComment extends AbstractDescribableImpl<MRComment> {

    /**
     * Result of job to match with this comment.
     */
    @Getter @Nonnull
    private final Result result;
    /**
     * Comment content.
     */
    @Getter @Nonnull
    private final String message;

    @Extension
    public static class DescriptorImpl extends Descriptor<MRComment> {

        @Override
        public String getDisplayName() {
            return "";
        }

        public ListBoxModel doFillResultItems() {
            return new ListBoxModel()
                    .add(Result.SUCCESS.toExportedObject())
                    .add(Result.UNSTABLE.toExportedObject())
                    .add(Result.FAILURE.toExportedObject())
                    .add(Result.ABORTED.toExportedObject());
        }

    }

}
