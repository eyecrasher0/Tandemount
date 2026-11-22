/* SPDX-License-Identifier: GPL-3.0-only */
package dev.eyecrasher.tandemount;

import java.io.StringReader;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

final class ConfigYaml extends YamlConfiguration {
    Settings loadedSettings;

    @Override public void loadFromString(String document) throws InvalidConfigurationException {
        Set<String> keys = explicitKeys(document);
        super.loadFromString(document);
        loadedSettings = Settings.read(this, keys);
    }

    private static Set<String> explicitKeys(String document) {
        // Bukkit loads into sections, which collapse duplicates and omit nulls.
        // Inspect the YAML nodes before that conversion, including merge keys.
        Node root = new Yaml().compose(new StringReader(document));
        if (root == null) return Collections.emptySet();
        if (!(root instanceof MappingNode) || !Tag.MAP.equals(root.getTag()))
            throw invalid(root, "configuration must be a mapping");
        checkMappings(root, new HashSet<Node>());
        Set<String> keys = new HashSet<String>();
        addMergedKeys(root, keys, new HashSet<Node>());
        return keys;
    }

    private static void checkMappings(Node node, Set<Node> seen) {
        if (node.isTwoStepsConstruction()) throw invalid(node, "recursive YAML aliases are invalid");
        if (!seen.add(node)) return;
        if (node instanceof MappingNode) {
            Set<String> keys = new HashSet<String>();
            for (NodeTuple entry : ((MappingNode)node).getValue()) {
                Node key = entry.getKeyNode();
                if (!(key instanceof ScalarNode) || !(Tag.STR.equals(key.getTag()) || Tag.MERGE.equals(key.getTag())))
                    throw invalid(key, "configuration keys must be strings");
                String name = ((ScalarNode)key).getValue();
                if (!keys.add(Tag.MERGE.equals(key.getTag()) ? null : name))
                    throw invalid(key, "duplicate configuration key");
                checkMappings(entry.getValueNode(), seen);
            }
        } else if (node instanceof SequenceNode) {
            for (Node element : ((SequenceNode)node).getValue()) checkMappings(element, seen);
        }
    }

    private static void addMergedKeys(Node node, Set<String> keys, Set<Node> seen) {
        if (!seen.add(node)) return;
        if (node instanceof MappingNode) {
            for (NodeTuple entry : ((MappingNode)node).getValue()) {
                ScalarNode key = (ScalarNode)entry.getKeyNode();
                if (Tag.MERGE.equals(key.getTag())) addMergedKeys(entry.getValueNode(), keys, seen);
                else keys.add(key.getValue());
            }
        } else if (node instanceof SequenceNode) {
            for (Node element : ((SequenceNode)node).getValue()) addMergedKeys(element, keys, seen);
        }
    }

    private static IllegalArgumentException invalid(Node node, String reason) {
        return new IllegalArgumentException("config.yml line " + (node.getStartMark().getLine() + 1) + ": " + reason);
    }
}
