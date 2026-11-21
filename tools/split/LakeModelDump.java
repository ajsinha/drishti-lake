// Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Lake. Proprietary: see LICENSE.
// The lake-file model check of step L5 (design section 7): every lake file of a folder read through Lakes.read, dumped as sorted JSON,
// one line per lake (no setting is named: each placeholder takes its default); a folder whose file refuses gives the refusal. Run over this code and, its import mapped back
// (lake_models.py), over the host's jars at the freeze commit: the two outputs must be equal.
import com.ash.lake.config.Lakes;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.util.TreeMap;

public final class LakeModelDump {
    public static void main(String[] args) throws Exception {
        ObjectMapper json = JsonMapper.builder().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
        for (String dir : args) {
            Lakes lakes;
            try {
                lakes = Lakes.read(Path.of(dir), k -> null);
            } catch (RuntimeException e) {                          // a folder whose file refuses: the refusal is the result
                System.out.println(dir + ": " + e.getMessage());
                continue;
            }
            new TreeMap<>(lakes.all()).forEach((name, lake) -> {
                try {
                    System.out.println(json.writeValueAsString(lake));
                } catch (Exception e) {
                    System.out.println(name + ": " + e);
                }
            });
        }
    }
}
