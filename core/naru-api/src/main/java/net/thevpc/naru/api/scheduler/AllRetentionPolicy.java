package net.thevpc.naru.api.scheduler;

import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NOperatorSymbol;

import java.util.Arrays;
import java.util.List;

/**
 * Composite — drop when ALL policies agree.
 */
public class AllRetentionPolicy implements NaruRetentionPolicy {
    private final List<NaruRetentionPolicy> policies;

    public AllRetentionPolicy(NaruRetentionPolicy... policies) {
        this.policies = Arrays.asList(policies);
    }

    public boolean shouldDrop(NaruEvent event) {
        return policies.stream().allMatch(p -> p.shouldDrop(event));
    }

    public long nextCheckMillis(NaruEvent event) {
        return policies.stream()
                .mapToLong(p -> p.nextCheckMillis(event))
                .min()
                .orElse(0);
    }

    @Override
    public NElement toElement() {
        NElement e=null;
        for (NaruRetentionPolicy policy : policies) {
            if(e==null){
                e=policy.toElement();
            }else{
                e=NElement.ofBinaryInfixOperator(NOperatorSymbol.AND, e, policy.toElement());
            }
        }
        if(e==null){
            return NElement.ofName("forget");
        }
        return e;
    }

    @Override
    public String toString() {
        return toElement().toString();
    }

}
