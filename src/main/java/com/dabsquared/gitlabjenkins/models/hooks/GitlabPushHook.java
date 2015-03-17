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
package com.dabsquared.gitlabjenkins.models.hooks;

import com.dabsquared.gitlabjenkins.models.attrs.GitlabCommitHookAttrs;

import lombok.Data;

/**
 * Push hook payload.
 *
 * @author Franta Mejta
 * @sa.date 2015-03-09T13:35:20+0100
 * @see http://doc.gitlab.com/ce/web_hooks/web_hooks.html#push-events
 */
@Data
public class GitlabPushHook {

    public static final String OBJECT_KIND = "push";
    private String before;
    private String after;
    private String ref;
    private int userId;
    private String userName;
    private int projectId;
    private Repository repository;
    private GitlabCommitHookAttrs[] commits;
    private int totalCommitsCount;
    /**
     * @since 7.7.0
     */
    private String checkoutSha;
    /**
     * @since 7.9.0
     */
    private String objectKind;
    /**
     * @since 7.9.0
     */
    private String userEmail;

    public final boolean isTagEvent() {
        return this.getRef().startsWith("refs/tags/");
    }

    @Data
    public static class Repository {

        private String name;
        private String url;
        private String description;
        private String homepage;
        /**
         * @since 7.8.0
         */
        private String gitHttpUrl;
        /**
         * @since 7.8.0
         */
        private String gitSshUrl;
        /**
         * @since 7.8.0
         */
        private int visibilityLevel;
    }

}
