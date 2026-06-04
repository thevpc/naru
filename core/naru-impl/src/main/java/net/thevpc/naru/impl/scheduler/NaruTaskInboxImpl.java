package net.thevpc.naru.impl.scheduler;

import java.util.List;

public class NaruTaskInboxImpl {
    private List<Long> inbox; // seqs only
    private long watermark;
}
