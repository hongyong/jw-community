package org.joget.apps.form.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.util.Collection;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.joget.apps.app.dao.FormDefinitionDao;
import org.joget.apps.app.model.AppDefinition;
import org.joget.apps.app.model.FormDefinition;
import org.joget.apps.app.service.AppService;
import org.joget.apps.app.service.AppUtil;
import org.joget.apps.form.model.Element;
import org.joget.apps.form.model.Form;
import org.joget.apps.form.model.FormData;
import org.joget.commons.util.LogUtil;
import org.joget.commons.util.StringUtil;
import org.joget.itextrenderer.ITextCustomFontResolver;
import org.joget.workflow.model.WorkflowAssignment;
import org.xhtmlrenderer.layout.SharedContext;
import org.xhtmlrenderer.pdf.ITextRenderer;
import org.xhtmlrenderer.resource.FSEntityResolver;

public class FormPdfUtil {
    public static byte[] createPdf(String formId, String primaryKey, AppDefinition appDef, WorkflowAssignment assignment, Boolean hideEmpty, String header, String footer, String css, Boolean showAllSelectOptions, Boolean repeatHeader, Boolean repeatFooter) {
        try {
            String html = getSelectedFormHtml(formId, primaryKey, appDef, assignment, hideEmpty);
            return createPdf(html, header, footer, css, showAllSelectOptions, repeatHeader, repeatFooter);
        } catch (Exception e) {
            LogUtil.error(FormPdfUtil.class.getName(), e, "");
        }
        return null;
    }
    
    public static byte[] createPdf(String html, String header, String footer, String css, Boolean showAllSelectOptions, Boolean repeatHeader, Boolean repeatFooter) {
        try {
            html = formatHtml(html, header, footer, css, showAllSelectOptions, repeatHeader, repeatFooter);
            
            final DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
            documentBuilderFactory.setValidating(false);
            DocumentBuilder builder = documentBuilderFactory.newDocumentBuilder();
            builder.setEntityResolver(FSEntityResolver.instance());
            org.w3c.dom.Document xmlDoc = builder.parse(new ByteArrayInputStream(html.getBytes("UTF-8")));

            ITextRenderer renderer = new ITextRenderer();
            SharedContext sharedContext = renderer.getSharedContext();
            sharedContext.setFontResolver(new ITextCustomFontResolver(sharedContext));
            
            renderer.setDocument(xmlDoc, null);

            ByteArrayOutputStream os = new ByteArrayOutputStream();
            renderer.layout();
            renderer.createPDF(os);
            byte[] output = os.toByteArray();
            os.close();
            return output;
        } catch (Exception e) {
            LogUtil.error(FormPdfUtil.class.getName(), e, "");
        }
        return null;
    }
    
    public static String getSelectedFormHtml(String formId, String primaryKey, AppDefinition appDef, WorkflowAssignment assignment, Boolean hideEmpty) {
        String html = "";

        AppService appService = (AppService) AppUtil.getApplicationContext().getBean("appService");
        FormData formData = new FormData();
        if (primaryKey != null && !primaryKey.isEmpty()) {
            formData.setPrimaryKeyValue(primaryKey);
        } else if (assignment != null) {
            formData.setPrimaryKeyValue(appService.getOriginProcessId(assignment.getProcessId()));
        }
        if (assignment != null) {
            formData.setProcessId(assignment.getProcessId());
        }
        
        Form form = null;
        FormDefinitionDao formDefinitionDao = (FormDefinitionDao) AppUtil.getApplicationContext().getBean("formDefinitionDao");
        FormService formService = (FormService) AppUtil.getApplicationContext().getBean("formService");
        FormDefinition formDef = formDefinitionDao.loadById(formId, appDef);
        String formJson = formDef.getJson();

        if (formJson != null) {
            formJson = AppUtil.processHashVariable(formJson, assignment, StringUtil.TYPE_JSON, null);
            form = (Form) formService.loadFormFromJson(formJson, formData);
        }
        
        if (hideEmpty != null && hideEmpty) {
            form = (Form) removeEmptyValueChild(form, form, formData);
        }
        
        if (form != null) {
            html = formService.retrieveFormHtml(form, formData);
        }

        return html;
    }
    
    public static String formatHtml(String html, String header, String footer, String css, Boolean showAllSelectOptions, Boolean repeatHeader, Boolean repeatFooter) {
        //remove hidden field
        html = html.replaceAll("<input[^>]*type=\"hidden\"[^>]*>", "");

        //remove form tag
        html = html.replaceAll("<form[^>]*>", "");
        html = html.replaceAll("</\\s?form>", "");

        //remove button
        html = html.replaceAll("<button[^>]*>[^>]*</\\s?button>>", "");

        //remove validator decorator
        html = html.replaceAll("<span\\s?class=\"[^\"]*cell-validator[^\"]?\"[^>]*>[^>]*</\\s?span>", "");

        //remove functional link
        html = html.replaceAll("<a\\s?href=\"[#|\\s]?\"[^>]*>.*?</\\s?a>", "");

        //remove link
        html = html.replaceAll("<link[^>]*>", "");

        //remove script
        html = html.replaceAll("(?s)<script[^>]*>.*?</script>", "");

        //remove style
        html = html.replaceAll("(?s)<style[^>]*>.*?</style>", "");

        //remove id
        html = html.replaceAll("id=\"([^\\\"]*)\"", "");
        
        if (showAllSelectOptions != null && showAllSelectOptions) {
            Pattern pattern = Pattern.compile("<label>[^<]*(<input[^>]*type=\\\"([^\\\"]*)\\\"[^>]*>)[^>]*</label>");
            Matcher matcher = pattern.matcher(html);
            while (matcher.find()) {
                String label = matcher.group(0);
                String input = matcher.group(1);
                String type = matcher.group(2);
                String replace = "";
                
                if (type.equalsIgnoreCase("checkbox") || type.equalsIgnoreCase("radio")) {
                    if (input.contains("checked")) {
                        replace = input;
                    } else {
                        replace = label;
                    }
                    html = html.replaceAll(StringUtil.escapeRegex(replace), "");
                }
            }
        }

        //convert input field
        Pattern pattern = Pattern.compile("<input[^>]*>");
        Matcher matcher = pattern.matcher(html);
        while (matcher.find()) {
            String inputString = matcher.group(0);

            //get the type
            Pattern patternType = Pattern.compile("type=\"([^\\\"]*)\"");
            Matcher matcherType = patternType.matcher(inputString);
            String type = "";
            if (matcherType.find()) {
                type = matcherType.group(1);
            }

            //get the value
            Pattern patternValue = Pattern.compile("value=\"([^\\\"]*)\"");
            Matcher matcherValue = patternValue.matcher(inputString);
            String value = "";
            if (matcherValue.find()) {
                value = matcherValue.group(1);
            }

            if (type.equalsIgnoreCase("text")) {
                html = html.replaceAll(StringUtil.escapeRegex(inputString), "<span>" + StringUtil.escapeRegex(value) + "</span>");
            } else if (type.equalsIgnoreCase("file") || type.equalsIgnoreCase("button") || type.equalsIgnoreCase("submit") || type.equalsIgnoreCase("reset") || type.equalsIgnoreCase("image")) {
                html = html.replaceAll(StringUtil.escapeRegex(inputString), "");
            } else if (type.equalsIgnoreCase("checkbox") || type.equalsIgnoreCase("radio")) {
                String replace = "<img alt=\"\" src=\"" + getResourceURL("/images/black_tick_n.png") + "\"/>";
                if (inputString.contains("checked")) {
                    replace = "<img alt=\"\" src=\"" + getResourceURL("/images/black_tick.png") + "\"/>";
                }
                html = html.replaceAll(StringUtil.escapeRegex(inputString), StringUtil.escapeRegex(replace));
            } else if (type.equalsIgnoreCase("password")) {
                html = html.replaceAll(StringUtil.escapeRegex(inputString), "<span>**********</span>");
            }
        }

        //convert selectbox
        Pattern patternSelect = Pattern.compile("<select[^>]*>.*?</select>", Pattern.DOTALL);
        Matcher matcherSelect = patternSelect.matcher(html);
        while (matcherSelect.find()) {
            String selectString = matcherSelect.group(0);
            String replace = ""; 
            int counter = 0;

            //get the type
            Pattern patternOption = Pattern.compile("<option[^>]*>(.*?)</option>");
            Matcher matcherOption = patternOption.matcher(selectString);
            while (matcherOption.find()) {
                String optionString = matcherOption.group(0);
                String label = matcherOption.group(1);

                if (optionString.contains("selected")) {
                    if (counter > 0) {
                        replace += ", ";
                    }
                    replace += label;
                }
            }

            if (counter > 0) {
                replace = "<span>" + replace + "</span>";
            }
            html = html.replaceAll(StringUtil.escapeRegex(selectString), StringUtil.escapeRegex(replace));
        }

        //convert textarea
        Pattern patternTextarea = Pattern.compile("<textarea[^>]*>.*?</textarea>", Pattern.DOTALL);
        Matcher matcherTextarea = patternTextarea.matcher(html);
        while (matcherTextarea.find()) {
            String textareaString = matcherTextarea.group(0);
            String replace = textareaString;
            replace = replace.replaceAll("<textarea[^>]*>", "");
            replace = replace.replaceAll("</textarea>", "");
            replace = replace.replaceAll("&lt;", "<");
            replace = replace.replaceAll("&gt;", ">");
            replace = replace.replaceAll("&nbsp;", " ");
            replace = replace.replaceAll("&quot;", "\"");

            if (!replace.contains("<p")) {
                String[] newline = replace.split("\\\n");
                if (newline.length > 1) {
                    replace = "";
                    for (String n : newline) {
                        if (!n.isEmpty()) {
                            String r = n.replaceAll("\\\n", "");
                            replace += "<p>" + r + "</p>";
                        }
                    }
                }
            }

            replace = "<div style=\"float:left;\">" + replace + "</div>";

            html = html.replaceAll(StringUtil.escapeRegex(textareaString), StringUtil.escapeRegex(replace));
        }

        //remove br
        html = html.replaceAll("</\\s?br>", "");

        //append style
        String style = "<style type='text/css'>";
        style += "*{font-size:12px;font-family:Arial, \"Droid Sans\";}";
        style += ".form-section, .subform-section {position: relative;overflow: hidden;margin-bottom: 10px;}";
        style += ".form-section-title span, .subform-section-title span {padding: 10px;margin-bottom: 10px;font-weight: bold;font-size: 16px;background: #efefef;display: block;}";
        style += ".form-column, .subform-column {position: relative;float: left;min-height: 20px;min-width: 250px;}";
        style += ".form-cell, .subform-cell {position: relative;min-height: 15px;color: black;clear: left;padding:3px 0px;}";
        style += ".form-cell > .label, .subform-cell > .label {width: 40%;display: block;float: left;font-weight:bold;}";
        style += "table {clear:both;}";
        style += "p {margin:5px 0;}";
        style += ".form-cell table td, .form-cell table th, .subform-cell table td, .subform-cell table th {border: solid 1px silver;padding: 3px;margin: 0px;}";
        style += ".subform-container{ border: 5px solid #dfdfdf;padding: 3px;margin-top:5px;}";
        style += ".subform-container, .subform-section {background: #efefef;}";
        style += ".subform-title{background: #efefef;position:relative;top:-12px;}";
        style += ".form-fileupload {float: left;}";
        style += ".form-cell-value, .subform-cell-value {float: left;width: 60%;}";
        style += ".form-cell-value label, .subform-cell-value label {display: block;float: left;width: 50%;}";
        
        if (repeatHeader != null && repeatHeader) {
            style += "div.header{display: block;position: running(header);}";
            style += "@page { @top-center { content: element(header) }}";
        }
        if (repeatFooter != null && repeatFooter) {    
            style += "div.footer{display: block;position: running(footer);}";
            style += "@page { @bottom-center { content: element(footer) }}";
        }
        
        if (css != null && !css.isEmpty()) {
            style += css;
        }
        
        style += "</style>";
        
        String headerHtml = ""; 
        if (header != null && !header.isEmpty()) {
            headerHtml = "<div class=\"header\">" + header + "</div>";
        }
        
        String footerHtml = ""; 
        if (footer != null && !footer.isEmpty()) {
            footerHtml = "<div class=\"footer\">" + footer + "</div>";
        }
        
        String htmlMeta = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
        htmlMeta += "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.1//EN\" \"http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd\">";
        htmlMeta += "<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"en\">";
        htmlMeta += "<head>";
        htmlMeta += "<meta http-equiv=\"content-type\" content=\"application/xhtml+xml; charset=UTF-8\" />";
        htmlMeta += style + "</head><body>";
                
        if (repeatFooter != null && repeatFooter) { 
            html = htmlMeta + headerHtml + footerHtml + html;
        } else {
            html = htmlMeta + headerHtml + html + footerHtml;
        }
        
        html += "</body></html>";
        
        return html;
    }
    
    public static Element  removeEmptyValueChild(Form form, Element element, FormData formData) {
        Collection<Element> childs = element.getChildren();
        if (childs != null && childs.size() > 0) {
            for (Iterator<Element> it = childs.iterator(); it.hasNext();) {
                Element c = it.next();
                if (removeEmptyValueChild(form, c, formData) == null) {
                    it.remove();
                } 
            }
            
            if (childs.isEmpty()) {
                return null;
            }
        } else {
            String value = FormUtil.getElementPropertyValue(element, formData);
            if (value == null || value.isEmpty()) {
                return null;
            }
        }
        
        return element;
    } 
    
    public static URL getResourceURL(String resourceUrl) {
        URL url = null;

        url = FormPdfUtil.class.getResource(resourceUrl);
        
        return url;
    }
}
