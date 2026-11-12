//! Pure SGF parsing and serialization, without interpreting property values.
//!
//! Property and value order (including repeated identifiers) is preserved. The
//! writer changes only line endings, escaping, and the game roots' `CA` property.

mod encoding;
mod parser;
mod raw;
mod writer;

pub use parser::parse;
pub use raw::{MAX_RAW_EVENTS, RawDocument, RawEvent, parse_kg_raw};
pub use writer::serialize;

/// A collection of games and diagnostics about the original input.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Collection {
    pub games: Vec<GameTree>,
    /// The decoder used, with a `-BOM` suffix when a byte order mark was present.
    /// Undeclared GBK is reported as GB18030, which includes GBK.
    pub encoding: String,
    pub warnings: Vec<Warning>,
}

/// An SGF sequence followed by its ordered variations.
///
/// A valid tree has at least one node in `sequence`. Keeping a sequence flat
/// avoids recursive traversal or destruction for ordinary long games.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GameTree {
    pub sequence: Vec<Node>,
    pub children: Vec<GameTree>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Node {
    pub properties: Vec<Property>,
}

/// Identifiers in parsed trees are nonempty uppercase ASCII letters. Each
/// property has at least one value, which may itself be empty. Values are
/// unescaped text with LF line endings; no SGF type-specific trimming occurs.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Property {
    pub identifier: String,
    pub values: Vec<String>,
}

/// A recoverable deviation from SGF or its declared character encoding.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Warning {
    /// Zero-based byte offset in decoded UTF-8 text (after removing the BOM).
    pub position: usize,
    pub kind: WarningKind,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum WarningKind {
    PropertyName {
        original: String,
        normalized: String,
    },
    DuplicateProperty {
        identifier: String,
    },
    MissingClosingParenthesis,
    UnescapedClosingBracket,
    EncodingMismatch {
        declared: String,
        detected: String,
    },
    UnknownEncoding {
        declared: String,
    },
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[error("{kind} at byte {position}")]
pub struct SgfError {
    /// Zero-based byte offset in decoded UTF-8 text for syntax errors; in the
    /// original input for decoding errors.
    pub position: usize,
    pub kind: ErrorKind,
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum ErrorKind {
    #[error("input cannot be decoded as {encoding}")]
    InvalidEncoding { encoding: String },
    #[error("expected a game tree")]
    ExpectedGameTree,
    #[error("expected a node after the opening parenthesis")]
    ExpectedNode,
    #[error("expected a bracketed property value")]
    ExpectedPropertyValue,
    #[error("missing closing parenthesis")]
    MissingClosingParenthesis,
    #[error("unterminated property value")]
    UnterminatedValue,
    #[error("a sequence cannot continue after variations")]
    NodeAfterVariation,
    #[error("unexpected character {0:?}")]
    UnexpectedCharacter(char),
    #[error("variation nesting exceeds the limit of {limit}")]
    NestingLimit { limit: usize },
    #[error("raw syntax event count exceeds the limit of {limit}")]
    RawEventLimit { limit: usize },
    #[error("raw syntax event bytes exceed the limit of {limit}")]
    RawSizeLimit { limit: usize },
}

#[cfg(test)]
mod tests;
