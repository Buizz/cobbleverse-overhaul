package dev.buizz.cobbleventure.region;

import dev.buizz.cobbleventure.api.RegionDefinition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RegionSetValidator {
    private final RegionDefinitionValidator definitionValidator = new RegionDefinitionValidator();

    public List<ValidationIssue> validate(List<RegionDefinition> regions) {
        List<ValidationIssue> issues = new ArrayList<>();
        Map<String, RegionDefinition> byId = new HashMap<>();

        for (RegionDefinition region : regions) {
            if (region == null) {
                issues.add(new ValidationIssue("regions", "지역 정의는 null일 수 없습니다."));
                continue;
            }
            issues.addAll(definitionValidator.validate(region));
            if (region.id() != null) {
                RegionDefinition previous = byId.putIfAbsent(region.id(), region);
                if (previous != null) {
                    issues.add(new ValidationIssue(region.id(), "지역 ID가 중복되었습니다."));
                }
            }
        }

        for (RegionDefinition region : regions) {
            if (region == null || region.connections() == null) {
                continue;
            }
            region.connections().forEach(connection -> {
                if (connection != null && !byId.containsKey(connection.target())) {
                    issues.add(new ValidationIssue(
                        region.id() + ".connections." + connection.gateId(),
                        "대상 지역을 찾을 수 없습니다: " + connection.target()
                    ));
                }
            });
        }

        for (int left = 0; left < regions.size(); left++) {
            RegionDefinition first = regions.get(left);
            for (int right = left + 1; right < regions.size(); right++) {
                RegionDefinition second = regions.get(right);
                if (first != null
                    && second != null
                    && first.dimension() != null
                    && first.bounds() != null
                    && second.bounds() != null
                    && first.dimension().equals(second.dimension())
                    && first.bounds().overlaps(second.bounds())) {
                    issues.add(new ValidationIssue(
                        first.id() + "/" + second.id(),
                        "같은 차원의 지역 경계가 겹칩니다."
                    ));
                }
            }
        }

        return List.copyOf(issues);
    }
}
