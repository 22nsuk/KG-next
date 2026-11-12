//! Experimental framed transport; the default retains the upstream experiment.
use kg_sgf_rust_probe::{MAX_FRAME, ParseMode, response_for_bytes};
use std::io::{self, Read, Write};

fn main() -> io::Result<()> {
    let mode = match std::env::args().nth(1).as_deref() {
        None => ParseMode::Upstream,
        Some("--kg-raw") => ParseMode::KgRaw,
        Some(_) => {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "expected --kg-raw or no arguments",
            ));
        }
    };
    let mut input = io::stdin().lock();
    let mut output = io::stdout().lock();
    loop {
        let mut size = [0; 4];
        match input.read(&mut size[..1])? {
            0 => return Ok(()),
            _ => input.read_exact(&mut size[1..])?,
        }
        let size = u32::from_be_bytes(size) as usize;
        if size > MAX_FRAME {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "probe input exceeds 64 MiB",
            ));
        }
        let mut bytes = vec![0; size];
        input.read_exact(&mut bytes)?;
        let response = response_for_bytes(&bytes, mode);
        output.write_all(&(response.len() as u32).to_be_bytes())?;
        output.write_all(&response)?;
        output.flush()?;
    }
}
