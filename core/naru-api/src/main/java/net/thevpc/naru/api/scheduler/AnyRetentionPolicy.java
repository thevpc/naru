package net.thevpc.naru.api.scheduler;

import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NOperatorSymbol;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Composite — drop when ANY policy says so.
 */
public class AnyRetentionPolicy implements NaruRetentionPolicy {
    private final List<NaruRetentionPolicy> policies;

    public AnyRetentionPolicy(NaruRetentionPolicy... policies) {
        this.policies = Arrays.asList(policies);
    }

    public boolean shouldDrop(NaruEvent event) {
        return policies.stream().anyMatch(p -> p.shouldDrop(event));
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
                e=NElement.ofBinaryInfixOperator(NOperatorSymbol.PIPE, e, policy.toElement());
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

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        AnyRetentionPolicy that = (AnyRetentionPolicy) o;
        return Objects.equals(policies, that.policies);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(policies);
    }
}
