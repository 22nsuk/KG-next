//! Experimental, framed full-document transport. Never used by the application.
use std::io::{self, Read, Write};
use std::time::Instant;

fn text(out: &mut Vec<u8>, value: &str) {
    out.extend_from_slice(&(value.len() as u32).to_be_bytes());
    out.extend_from_slice(value.as_bytes());
}

fn tree(out: &mut Vec<u8>, game: &sgf::GameTree) {
    out.push(b'(');
    for node in &game.sequence {
        out.push(b';');
        for property in &node.properties {
            for value in &property.values {
                out.push(b'P');
                text(out, &property.identifier);
                text(out, value);
            }
        }
    }
    for child in &game.children {
        tree(out, child);
    }
    out.push(b')');
}

fn main() -> io::Result<()> {
    let mut input = io::stdin().lock();
    let mut output = io::stdout().lock();
    loop {
        let mut size = [0; 4];
        match input.read(&mut size[..1])? {
            0 => return Ok(()),
            _ => input.read_exact(&mut size[1..])?,
        }
        let size = u32::from_be_bytes(size) as usize;
        if size > 64 * 1024 * 1024 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "probe input exceeds 64 MiB"));
        }
        let mut bytes = vec![0; size];
        input.read_exact(&mut bytes)?;
        let started = Instant::now();
        let parsed = sgf::parse(&bytes);
        let elapsed = started.elapsed().as_nanos() as u64;
        let mut response = Vec::new();
        response.extend_from_slice(&elapsed.to_be_bytes());
        match parsed {
            Ok(collection) => {
                response.push(0);
                text(&mut response, &collection.encoding);
                response.extend_from_slice(&(collection.warnings.len() as u32).to_be_bytes());
                for warning in &collection.warnings {
                    text(&mut response, &format!("{}: {:?}", warning.position, warning.kind));
                }
                for game in &collection.games {
                    tree(&mut response, game);
                }
            }
            Err(error) => {
                response.push(1);
                text(&mut response, &error.to_string());
            }
        }
        output.write_all(&(response.len() as u32).to_be_bytes())?;
        output.write_all(&response)?;
        output.flush()?;
    }
}
