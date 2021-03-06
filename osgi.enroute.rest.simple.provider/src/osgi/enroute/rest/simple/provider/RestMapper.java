package osgi.enroute.rest.simple.provider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.osgi.dto.DTO;

import aQute.lib.collections.ExtList;
import aQute.lib.collections.MultiMap;
import aQute.lib.converter.Converter;
import aQute.lib.getopt.Options;
import aQute.lib.hex.Hex;
import aQute.lib.io.IO;
import aQute.lib.json.Decoder;
import aQute.lib.json.JSONCodec;
import osgi.enroute.rest.api.BadRequest400Exception;
import osgi.enroute.rest.api.Forbidden403Exception;
import osgi.enroute.rest.api.Inf;
import osgi.enroute.rest.api.NotFound404Exception;
import osgi.enroute.rest.api.NotImplemented501Exception;
import osgi.enroute.rest.api.REST;
import osgi.enroute.rest.api.RESTRequest;

/**
 * Utility code to map a web request to a object and method request efficiently.
 * It uses the reflection information in the registered object's methods to
 * convert the parameters to an appropriate value.
 * <p>
 * A method is applicable if it starts with the web requests method
 * (GET/PUT/DELETE/OPTION/HEAD etc) and then in its lower case form. The
 * remaining name (with the first character made upper case) is the first
 * segment after this mapper's prefix. So /rest/name with PUT request is mapped
 * to methods with the name {@code putName}.
 * <p>
 * Applicable methods are methods that start with a an interface argument that
 * can be backed by a Map; this map will contain the web request's arguments,
 * i.e. the parameters after the question mark. The {@link Options} interface
 * has methods that provide access to the underlying servlet request and
 * response.
 * <p>
 * 
 * <pre>
 * interface MyOptions extends RESTRequest {
 * 	int[] value();
 * }
 * 
 * int getFoo(MyOptions options) {
 * 	int sum = 1;
 * 	for (int i : options.value())
 * 		sum *= sum;
 * 	return sum;
 * }
 * // is mapped from https://localhost:8080/rest?value=45
 * 
 * </pre>
 * 
 * Since rest is supposed to carry the arguments in the URL, the segments in the
 * URL after the method name are mapped to arguments, potentially varargs. For
 * example:
 * 
 * <pre>
 * interface MyOptions extends RESTRequest {
 * }
 * 
 * Attr getFoo(MyOptions options, byte[] id, int attr) {
 * 	return getObject(id).getAttr(attr);
 * }
 * 
 * // is mapped from https://localhost:8080/rest/0348E767F0/23
 * 
 * </pre>
 * 
 * For POST and PUT requests, the Options interface provides access to the
 * underlying stream. Just declare a field _ on the Options interface and this
 * method will return the converted value from the JSON decoded input stream:
 * 
 * <pre>
 * interface MyOptions extends RESTRequest {
 * 	Person _();
 * }
 * 
 * int getFoo(MyOptions options) {
 * 	Person p = options._();
 * 	return p.age;
 * }
 * // is mapped from a POST/PUT https://localhost:8080/rest
 * 
 * </pre>
 */
public class RestMapper {
	
	enum Verb {
		get,post,put,delete,option,head;
	}
	EnumSet<Verb> PAYLOAD = EnumSet.of(Verb.post,Verb.put);
	
	final static JSONCodec codec = new JSONCodec();
	MultiMap<String, Function> functions = new MultiMap<String, Function>();
	boolean diagnostics;
	Converter converter = new Converter();

	{
		converter.hook(byte[].class, new Converter.Hook() {

			@Override
			public Object convert(Type dest, Object o) throws Exception {
				if (o instanceof String)
					return Hex.toByteArray((String) o);

				return null;
			}
		});
	}

	/**
	 * Cache for information about the discovered methods to speed up the
	 * mapping process.
	 */
	class Function {
		final Method method;
		final Object target;
		final String name;
		final int cardinality;
		final boolean varargs;
		final Type post;
		final boolean hasRequestParameter;
		final boolean hasPayloadAsParameter;

		public Function(Object target, Method method, Verb verb) {
			this.target = target;
			this.method = method;
			int cardinality = method.getParameterTypes().length;

			// check if the first parameter is of type RestRequest
			// and whether it has a _body() method defining the post Type

			Type requestBody = null;
			boolean hasRequesParam = false;
			if (cardinality > 0)
				try {
					Class<?> rc = method.getParameterTypes()[0];
					if (RESTRequest.class.isAssignableFrom(rc)) {
						hasRequesParam = true;
						cardinality--; // don't count as cardinality
					}

					requestBody = rc.getMethod("_body").getGenericReturnType();
				} catch (Exception e) {
					// Ignore
				}
			this.hasRequestParameter = hasRequesParam;

			// if method starts with put/post, and no _body method defined,
			// then convert payload data to first method parameter after
			// RestRequest in this case this parameter does not count for 
			// the cardinality

			if (requestBody == null && (PAYLOAD.contains(verb))) {
				if ( cardinality == 0)
					throw new IllegalArgumentException("Invalid " + verb +" method " + method.getName() +". A payload method " + PAYLOAD + " must have a RESTRequest subclass with a _body method defined or a parameter that acts as the body type.");
				requestBody = method.getParameterTypes()[hasRequestParameter ? 1 : 0];
				cardinality--; // don't count as cardinality
				hasPayloadAsParameter = true;
			} else {
				hasPayloadAsParameter = false;
			}

			this.post = requestBody;

			this.cardinality = cardinality;

			if ((varargs = method.isVarArgs()))
				this.name = method.getName().toLowerCase();
			else
				this.name = method.getName().toLowerCase() + "/" + this.cardinality;
		}

		public Type getPayloadType() {
			return post;
		}

		public String getName() {
			return name;
		}

		public String toString() {
			return name;
		}

		/**
		 * This is the heart, it tries to map the parameters to this method.
		 * 
		 * @param args
		 *            mapped to the Options interface (first parameter)
		 * @param list
		 *            the parameters of the call (segments in the url)
		 * @return the converted arguments or null if no possible match
		 */
		public Object[] match(Map<String, Object> args, List<String> list) throws Exception {
			Object[] parameters = new Object[cardinality + (hasRequestParameter ? 1 : 0) + (hasPayloadAsParameter ? 1 : 0)];
			Type[] types = method.getGenericParameterTypes();
			if (hasRequestParameter) {
				parameters[0] = converter.convert(types[0], args);
			}

			for (int i = (hasRequestParameter ? 1 : 0) + (hasPayloadAsParameter ? 1 : 0); i < parameters.length; i++) {
				if (varargs && i == parameters.length - 1) {
					parameters[i] = converter.convert(types[i], list);
				} else {
					// varargs but not enough to fill the constants
					if (list.isEmpty())
						return null;

					String removed = list.remove(0);
					parameters[i] = converter.convert(types[i], removed);
				}
			}
			return parameters;
		}

		/**
		 * Invoke the method
		 * 
		 * @param parameters
		 *            the Options interface and optional segments
		 * @return the result of the invocation
		 */
		public Object invoke(Object[] parameters)
				throws IllegalArgumentException, IllegalAccessException, InvocationTargetException {
			return method.invoke(target, parameters);
		}
	}

	public static class InfDTO extends DTO {
	    public List<MethodDTO> methods = new ArrayList<>();
        // We could do this, but if the inf is outward facing, then what's the point??
//        public List<MethodDTO> hidden = new ArrayList<>();
	}

	public static class MethodDTO extends DTO {
	    public String name;
	    public String method;
	    public String description;
//        public String payloadType;
//        public boolean hasRequestParameter;
//        public boolean hasPayloadAsParameter;
	    public List<ArgDTO> arguments = new ArrayList<>();
	    public List<ArgDTO> parameters = new ArrayList<>();
	}

	public static class ArgDTO extends DTO {
	    public String name;
	    public String description;
	}

	/**
	 * Add a new resource manager. Add all public methods that have the first
	 * argument be an interface that extends {@link Options}.
	 */
	final static Pattern ACCEPTED_METHOD_NAMES_P = Pattern.compile("(?<verb>get|post|put|delete|option|head)[A-Z0-9]");

	public synchronized void addResource(REST resource) {
		for (Method m : resource.getClass().getMethods()) {
			if (m.getDeclaringClass() == Object.class)
				continue;

			// restrict to methods starting with HTTP request prefix
			String methodName = m.getName();
			Matcher matcher = ACCEPTED_METHOD_NAMES_P.matcher(methodName);
			if (!matcher.lookingAt())
				continue;

			Verb verb = Verb.valueOf(Verb.class,matcher.group("verb"));
			
			Function function = new Function(resource, m, verb);
			functions.add(function.getName(), function);
		}
	}

	/**
	 * Remove a prior registered resource
	 */
	public synchronized void removeResource(REST resource) {
		for (String s : functions.keySet()) {
			List<Function> fs = functions.get(s);
			Iterator<Function> i = fs.iterator();
			while (i.hasNext()) {
				Function f = i.next();
				if (f.target == resource)
					i.remove();
			}
		}
	}

	/**
	 * Execute a web request.
	 * 
	 * @param rq
	 *            the request
	 * @param rsp
	 *            the response
	 * @return true if we matched and executed
	 * @throws IOException
	 */
	public boolean execute(HttpServletRequest rq, HttpServletResponse rsp) throws IOException {
        try {
            String path = rq.getPathInfo();
            if (path == null)
                throw new IllegalArgumentException(
                        "The rest servlet requires that the name of the resource follows the servlet path ('rest'), like /rest/aQute.service.library.Program[/...]*[?...]");

            if (path.startsWith("/"))
                path = path.substring(1);

            if (path.equals("inf")) {
                InfDTO result = printInf();
                printOutput(rq, rsp, result);
                return true;
            }

            //
            // Find the method's arguments embedded in the url
            //
            String[] segments = path.split("/");
            ExtList<String> list = new ExtList<String>(segments);
            String name = (rq.getMethod() + list.remove(0)).toLowerCase();
            int cardinality = list.size();

            //
            // We register methods with their cardinality to not have
            // to wade through them one by one
            //
            List<Function> candidates = functions.get(name + "/" + cardinality);
            if (candidates == null)
                candidates = functions.get(name);

            //
            // check if we found a suitable candidate
            //

            if (candidates == null)
                throw new FileNotFoundException("No such method " + name + "/" + cardinality + ". Available: " + functions);

            //
            // All values are arrays, turn them into singletons when
            // they have one element
            //
            Map<String, Object> args = new HashMap<String, Object>(rq.getParameterMap());
            for (Map.Entry<String, Object> e : args.entrySet()) {
                Object o = e.getValue();
                if (o.getClass().isArray()) {
                    if (Array.getLength(o) == 1)
                        e.setValue(Array.get(o, 0));
                }
            }

            //
            // Provide the context variables through the Options interface
            //
            args.put("_request", rq);
            args.put("_host", rq.getHeader("Host"));
            args.put("_response", rsp);

            if (candidates.isEmpty())
                return false;                

            Function f = candidates.get(0);
            Object[] parameters = f.match(args, list);
            if (parameters != null) {

                Type type = f.getPayloadType();
                if (type != null) {
                    Object payload = null;
                    Decoder d = codec.dec().from(rq.getInputStream());
                    if(!d.isEof())
                        payload = d.get(type);

                    if (f.hasPayloadAsParameter) {
                        parameters[f.hasRequestParameter ? 1 : 0] = payload;
                    }

                    if(payload != null)
                        args.put("_body", payload);
                }

                try {
                    Object result = f.invoke(parameters);
                    if (result != null )
                        printOutput(rq, rsp, result);
                } catch (InvocationTargetException e1) {
                    throw e1.getTargetException();
                }
            }
		} catch (NotFound404Exception | FileNotFoundException e) {
			rsp.setStatus(HttpServletResponse.SC_NOT_FOUND);
		} catch (Forbidden403Exception | SecurityException e) {
			rsp.setStatus(HttpServletResponse.SC_FORBIDDEN);
		} catch (NotImplemented501Exception | UnsupportedOperationException e) {
			rsp.setStatus(HttpServletResponse.SC_NOT_IMPLEMENTED);
		} catch (BadRequest400Exception | IllegalStateException e) {
			rsp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
		} catch (Throwable e) {
			e.printStackTrace();
			rsp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		}
		return true;
	}

	public void setDiagnostics(boolean on) {
		this.diagnostics = true;
	}

    @SuppressWarnings("unchecked")
	private void printOutput(HttpServletRequest rq, HttpServletResponse rsp, Object result) throws Exception {
        //
        // Check if we can compress the result
        //
        OutputStream out = rsp.getOutputStream();

        if (result != null) {
            //
            // < 14 bytes screws up
            //
            if (!(result instanceof Number)
                    && !(result instanceof String && ((String) result).length() < 100)) {
                String acceptEncoding = rq.getHeader("Accept-Encoding");
                if (acceptEncoding != null) {
                    boolean gzip = acceptEncoding.indexOf("gzip") >= 0;
                    boolean deflate = acceptEncoding.indexOf("deflate") >= 0;

                    if (gzip) {
                        out = new GZIPOutputStream(out);
                        rsp.setHeader("Content-Encoding", "gzip");
                    } else if (deflate) {
                        out = new DeflaterOutputStream(out);
                        rsp.setHeader("Content-Encoding", "deflate");
                    }
                }
            }

            //
            // Convert based on the returned object
            // Streams, byte[], File, and CharSequence
            // are written without conversion. Other objects
            // are written with json
            //

            if (result instanceof InputStream) {
                IO.copy((InputStream) result, out);
            } else if (result instanceof byte[]) {
                byte[] data = (byte[]) result;
                rsp.setContentLength(data.length);
                out.write(data);
            } else if (result instanceof File) {
                File fresult = (File) result;
                rsp.setContentLength((int) fresult.length());
                IO.copy(fresult, out);
            } else {
                rsp.setContentType("application/json;charset=UTF-8");
                if (result instanceof Iterable)
                    result = new ExtList<Object>((Iterable<Object>) result);
                codec.enc().writeDefaults().to(out).put(result);
            }
        }
        out.close();
	}

    private InfDTO printInf() {
        InfDTO dto = new InfDTO();
        for (String key : functions.keySet()) {
            List<Function> list = functions.get(key);
            for (int i = 0; i < list.size(); i++) {
                MethodDTO methodDto = toDto(list.get(i));
                if (i == 0)
                    dto.methods.add(methodDto);
                // We could do this, but if the inf is outward facing, then what's the point??
//                else
//                    dto.hidden.add(methodDto);
            }
        }

        return dto;
    }

    private MethodDTO toDto(Function f) {
        MethodDTO dto = new MethodDTO();
        dto.method = extractHttpMethod(f.name);
        dto.name = f.name.substring(dto.method.length());
        dto.name = dto.name.substring(0, dto.name.indexOf("/"));
        Inf inf = f.method.getAnnotation(Inf.class);
        dto.description = inf != null ? inf.value() : "";
//        dto.payloadType = f.post != null ? f.post.getTypeName() : "";
//        dto.hasRequestParameter = f.hasPayloadAsParameter;
//        dto.hasPayloadAsParameter = f.hasPayloadAsParameter;
        for (int i = 0; i < f.method.getParameterCount(); i++) {
            Parameter p = f.method.getParameters()[i];
            Class<?> pt = f.method.getParameterTypes()[i];
            if (RESTRequest.class.isAssignableFrom(pt))
                dto.parameters.add(toDto(f.method, p, i));
            else
                dto.arguments.add(toDto(f.method, p, i));
        }
        return dto;
    }

    private String extractHttpMethod(String name) {
        Pattern p = Pattern.compile("(?<verb>get|post|put|delete|option|head)");
        Matcher matcher = p.matcher(name);

        if(matcher.lookingAt())
            return matcher.group("verb");

        return "???";
    }

    private ArgDTO toDto(Method m, Parameter p, int index) {
        ArgDTO dto = new ArgDTO();
        dto.name = p.getName();
        Inf inf = p.getAnnotation(Inf.class);
        dto.description = inf != null ? inf.value() : "";
        return dto;
    }
}
