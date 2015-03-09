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
package com.dabsquared.gitlabjenkins.models;

import lombok.Data;

/**
 * Push event payload.
 *
 * @author Franta Mejta
 * @sa.date 2015-03-09T13:35:20+0100
 * @see http://doc.gitlab.com/ce/web_hooks/web_hooks.html#push-events
 */
@Data
public class GitlabPushEvent {

    private String before;
    private String after;
    private String ref;
    private int userId;
    private String userName;
    private int projectId;
    private Repository repository;
    private Commit[] commits;
    private int totalCommitsCount;

    public final boolean isTagEvent() {
        return this.getRef().startsWith("refs/tags/");
    }

    @Data
    public static class Repository {

        private String name;
        private String url;
        private String description;
        private String homepage;
        private String gitHttpUrl;
        private String gitSshUrl;
        private int visibilityLevel;
    }

    @Data
    public static class Commit {

        private String id;
        private String message;
        private String timestamp;
        private String url;
        private Author author;
    }

    @Data
    public static class Author {

        private String name;
        private String email;
    }

}
