package net.thevpc.naru.api.skills;

import net.thevpc.naru.api.agent.NaruResourceInfo;

import java.util.List;

public interface NaruSkillManager {
    NaruResourceInfo findSkillInfo(String name);
    NaruSkill findSkill(String name);

    List<NaruResourceInfo> available();
}
