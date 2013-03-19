/*
 * Originally written by Matthew Donoughe [mdonoughe], source...
 *   https://github.com/mdonoughe/jgit-describe
 * Updated to latest JGIT by Shane Mc Cormack [ShaneMcC], source...
 *   https://github.com/ShaneMcC/jgit-describe
 */
package org.mdonoughe;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import java.util.regex.Pattern;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.revwalk.FollowFilter;

public class GitDescribe {
    private String bestTag;
    private int distance;
    private String hash;
    private boolean dirty;
    private boolean showDirty;
    
    private GitDescribe(final String bestTag, final int distance, 
            final String hash, final boolean dirty, final boolean showDirty) {
        this.bestTag= bestTag;
        this.distance= distance;
        this.hash= hash;
        this.dirty= dirty;
        this.showDirty= showDirty;
    }
    
    public String getBestTag() { return bestTag; }
    public int getDistance() { return distance; }
    public String getHash() { return hash; }
    public boolean isDirty() { return dirty; }

    @Override
    public String toString() {
        String dirtySuffix= (showDirty && dirty) ? "-dirty" : ""; 
        return String.format("%s-%d-g%s%s", bestTag, distance, hash, dirtySuffix);
    }
    
    static class Builder {
        private File workTreeDir;
        private int shalength= 7;
        private String ref= "HEAD";
        private String subdir;
        private boolean showDirty= false;
        
        public Builder(final File workTreeDir) {
            this.workTreeDir= workTreeDir;
        }
        
        public Builder setShalength(final int shalength) {
            this.shalength= shalength;
            return this;
        }
        
        public Builder setRef(final String ref) {
            this.ref= ref;
            return this;
        }
        
        public Builder setSubdir(final String subdir) {
            this.subdir= subdir;
            return this;
        }        
        
        public Builder setShowDirty(final boolean showDirty) {
            this.showDirty= showDirty;
            return this;
        }

        public GitDescribe build() throws Exception {
            Repository repository = null;
            try {
                RepositoryBuilder builder = new RepositoryBuilder();
                repository = builder.setWorkTree(workTreeDir)
                        .findGitDir().build();
            } catch(IOException e) {
                throw new Exception("Could not open repository", e);
            }

            RevWalk walk = null;
            RevCommit start = null;
            try {
                walk = getWalk(repository);
                start = walk.parseCommit(repository.resolve(ref));
                walk.markStart(start);
                if (subdir != null) {
                    final RevCommit next = walk.next();
                    if (next != null) {
                        walk = getWalk(repository);
                        start = walk.parseCommit(next);
                    }
                }
            } catch (IOException e) {
                throw new Exception("Could not find target", e);
            }

            final Map<ObjectId, String> tags = new HashMap<ObjectId,String>();

            for (Map.Entry<String, Ref> tag : repository.getTags().entrySet()) {
                try {
                    RevTag r = walk.parseTag(tag.getValue().getObjectId());
                    ObjectId taggedCommit = r.getObject().getId();
                    tags.put(taggedCommit, tag.getKey());
                } catch (IOException e) {
                    // Theres really no need to panic yet.
                }
            }

            // No tags found. Panic.
            if (tags.isEmpty()) {
                throw new Exception("No tags found.");
            }

            final List<RevCommit> taggedParents = taggedParentCommits(walk, start, tags);
            RevCommit best = null;
            int bestDistance = 0;
            for (RevCommit commit : taggedParents) {
                int distance = distanceBetween(start, commit);
                if (best == null || (distance < bestDistance)) {
                    best = commit;
                    bestDistance = distance;
                }
            }

            String bestTag= (best != null) ? tags.get(best.getId()) : "";
            String hash= start.getId().abbreviate(shalength).name();
            boolean dirty= false;
            try {
                Git git= new Git(repository);
                Status status= git.status().call();
                dirty= ! status.isClean();
            } catch (GitAPIException e) { /*ignore*/ }

            return new GitDescribe(bestTag, bestDistance, hash, dirty, showDirty);
        }

        /**
         * Get a Revision Walker instance set up with the correct tree filter.
         *
         * @param repository Repository that should be walked.
         * @return RevWalker instance with Tree Filter if required.
         * @throws BuildException If the given subdir is invalid.
         */
        private RevWalk getWalk(final Repository repository) throws Exception {
            RevWalk walk = null;
            walk = new RevWalk(repository);

            if (subdir != null) {
                final String parent = repository.getDirectory().getParent() + File.separator;
                for (String sd : subdir.split(";")) {
                    sd = sd.replaceFirst("^" + Pattern.quote(parent), "");
                    if (!new File(parent + sd).exists()) {
                        throw new Exception("'"+sd+"' does not appear to be a subdir of this repo.");
                    }
                    // jgit is stupid on windows....
                    final String filterDir = (File.separatorChar == '\\') ? sd.replace('\\', '/') : sd;
                    walk.setTreeFilter(FollowFilter.create(filterDir));
                }
            }

            return walk;
        }

        /**
         * This does something. I think it gets every possible parent tag this
         * commit has, then later we look for which is closest and use that as
         * the tag to describe. Or something like that.
         *
         * @param walk
         * @param child
         * @param tagmap
         * @return
         * @throws BuildException
         */
        private List<RevCommit> taggedParentCommits(final RevWalk walk, final RevCommit child, final Map<ObjectId, String> tagmap) {
            final Queue<RevCommit> q = new LinkedList<RevCommit>();
            q.add(child);
            final List<RevCommit> taggedcommits = new LinkedList<RevCommit>();
            final Set<ObjectId> seen = new HashSet<ObjectId>();

            while (q.size() > 0) {
                final RevCommit commit = q.remove();
                if (tagmap.containsKey(commit.getId())) {
                    taggedcommits.add(commit);
                    // don't consider commits that are farther away than this tag
                    continue;
                }
                for (RevCommit p : commit.getParents()) {
                    if (!seen.contains(p.getId())) {
                        seen.add(p.getId());
                        try {
                            q.add(walk.parseCommit(p.getId()));
                        } catch (IOException e) {
                            throw new RuntimeException("Parent not found", e);
                        }
                    }
                }
            }
            return taggedcommits;
        }

        /**
         * Calculate the distance between 2 given commits, parent and child.
         *
         * @param child Commit to calculate distance to (The latest commit)
         * @param parent Commit to calculate distance from (The last tag)
         * @return Numeric value between the 2 commits.
         */
        private int distanceBetween(final RevCommit child, final RevCommit parent) {
            final Set<ObjectId> seen = new HashSet<ObjectId>();
            final Queue<RevCommit> q1 = new LinkedList<RevCommit>();
            final Queue<RevCommit> q2 = new LinkedList<RevCommit>();

            q1.add(child);
            int distance = 1;
            while ((q1.size() > 0) || (q2.size() > 0)) {
                if (q1.size() == 0) {
                    distance++;
                    q1.addAll(q2);
                    q2.clear();
                }
                final RevCommit commit = q1.remove();
                if (commit.getParents() == null) {
                    return 0;
                } else {
                    for (RevCommit p : commit.getParents()) {
                        if (p.getId().equals(parent.getId())) {
                            return distance;
                        }
                        if (!seen.contains(p.getId())) {
                            q2.add(p);
                        }
                    }
                }
                seen.add(commit.getId());
            }
            return distance;
        }
    }
}