use crate::{Collection, GameTree, Node};

/// Serialize valid SGF trees to UTF-8. This does not modify the collection.
/// Each game's root has exactly one `CA[UTF-8]`; all other properties retain
/// their order, multiplicity and text (with CR/LF line endings normalized).
pub fn serialize(collection: &Collection) -> String {
    enum Action<'a> {
        Tree(&'a GameTree, bool),
        Close,
    }
    let mut output = String::new();
    let mut actions = Vec::new();
    for game in &collection.games {
        actions.push(Action::Tree(game, true));
        while let Some(action) = actions.pop() {
            match action {
                Action::Close => output.push(')'),
                Action::Tree(tree, is_game_root) => {
                    output.push('(');
                    for (index, node) in tree.sequence.iter().enumerate() {
                        write_node(&mut output, node, is_game_root && index == 0);
                    }
                    actions.push(Action::Close);
                    for child in tree.children.iter().rev() {
                        actions.push(Action::Tree(child, false));
                    }
                }
            }
        }
        output.push('\n');
    }
    output
}

fn write_node(output: &mut String, node: &Node, is_root: bool) {
    output.push(';');
    let mut wrote_encoding = false;
    for property in &node.properties {
        if is_root && property.identifier == "CA" {
            if !wrote_encoding {
                output.push_str("CA[UTF-8]");
                wrote_encoding = true;
            }
            continue;
        }
        output.push_str(&property.identifier);
        for value in &property.values {
            output.push('[');
            write_value(output, value);
            output.push(']');
        }
    }
    if is_root && !wrote_encoding {
        output.push_str("CA[UTF-8]");
    }
}

fn write_value(output: &mut String, value: &str) {
    let mut chars = value.chars().peekable();
    while let Some(ch) = chars.next() {
        match ch {
            '\\' | ']' => {
                output.push('\\');
                output.push(ch);
            }
            '\r' | '\n' => {
                if matches!((ch, chars.peek()), ('\r', Some('\n')) | ('\n', Some('\r'))) {
                    chars.next();
                }
                output.push('\n');
            }
            _ => output.push(ch),
        }
    }
}
