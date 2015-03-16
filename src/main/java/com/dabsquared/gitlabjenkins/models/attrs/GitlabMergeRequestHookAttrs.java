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
package com.dabsquared.gitlabjenkins.models.attrs;

import java.util.Locale;

import org.codehaus.jackson.annotate.JsonCreator;
import org.joda.time.DateTime;

import lombok.Data;

/**
 * Merge request attributes.
 *
 * @author Franta Mejta
 * @sa.date 2015-03-11T13:19:10+0100
 */
@Data
public class GitlabMergeRequestHookAttrs {

    /* hook attributes */
    private GitlabProjectHookAttrs source;
    private GitlabProjectHookAttrs target;
    private GitlabCommitHookAttrs lastCommit;

    /* merge request attributes */
    private Integer id;
    private String targetBranch;
    private String sourceBranch;
    private Integer sourceProjectId;
    private Integer authorId;
    private Integer assigneeId;
    private String title;
    private DateTime createdAt;
    private DateTime updatedAt;
    private Integer milestoneId;
    private State state;
    private MergeStatus mergeStatus;
    private Integer targetProjectId;
    private Integer iid;
    private String description;

    public enum State {

        OPENED, REOPENED, CLOSED, MERGED, LOCKED;

        @JsonCreator
        public static State fromJson(final String value) {
            return State.valueOf(value.toUpperCase(Locale.ROOT));
        }

    }

    public enum MergeStatus {

        UNCHECKED, CAN_BE_MERGED, CANNOT_BE_MERGED;

        @JsonCreator
        public static MergeStatus fromJson(final String value) {
            return MergeStatus.valueOf(value.toUpperCase(Locale.ROOT));
        }

    }

}
